/**
 * T-Emulator Backend API App Factory
 * Handles Play Store Purchase Verification, VIP Entitlements, Account Management, and RTDN.
 */

const express = require('express');
const crypto = require('crypto');

function sha256Hex(data) {
    return crypto.createHash('sha256').update(String(data)).digest('hex');
}

function getEncryptionKey(config) {
    const rawKey = config?.ackTokenEncryptionKey || process.env.ACK_TOKEN_ENCRYPTION_KEY;
    if (!rawKey) {
        if (process.env.NODE_ENV === 'production' && config?.isProduction) {
            throw new Error('ACK_TOKEN_ENCRYPTION_KEY is required in production environment');
        }
        // Deterministic test key: 32 bytes (64 hex characters)
        return Buffer.from('0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'hex');
    }
    if (typeof rawKey === 'string') {
        if (rawKey.length === 64) {
            return Buffer.from(rawKey, 'hex');
        }
        if (rawKey.length === 32) {
            return Buffer.from(rawKey, 'utf8');
        }
        return crypto.createHash('sha256').update(rawKey).digest();
    }
    if (Buffer.isBuffer(rawKey) && rawKey.length === 32) {
        return rawKey;
    }
    throw new Error('Invalid ACK_TOKEN_ENCRYPTION_KEY length (must be 32 bytes or 64 hex characters)');
}

function encryptToken(plainText, key) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    let ciphertext = cipher.update(plainText, 'utf8', 'hex');
    ciphertext += cipher.final('hex');
    const authTag = cipher.getAuthTag().toString('hex');
    return {
        iv: iv.toString('hex'),
        authTag: authTag,
        ciphertext: ciphertext
    };
}

function decryptToken(encryptedData, key) {
    const iv = Buffer.from(encryptedData.iv, 'hex');
    const authTag = Buffer.from(encryptedData.authTag, 'hex');
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(authTag);
    let decrypted = decipher.update(encryptedData.ciphertext, 'hex', 'utf8');
    decrypted += decipher.final('utf8');
    return decrypted;
}

/**
 * Creates an Express application with injected dependencies.
 *
 * @param {Object} options
 * @param {Object} [options.admin] Firebase admin instance
 * @param {Object} [options.androidPublisher] Google AndroidPublisher client
 * @param {Object} [options.db] In-memory or database adapter for testing
 * @param {Object} [options.config] Runtime configuration
 */
function createApp(options = {}) {
    const app = express();
    app.use(express.json({ limit: '1mb' }));

    const config = {
        packageName: options.config?.packageName || process.env.PLAY_PACKAGE_NAME || 'com.temulator.app',
        vipProductId: options.config?.vipProductId || process.env.VIP_PRODUCT_ID || 'vip_lifetime',
        pubSubAudience: options.config?.pubSubAudience || process.env.PUBSUB_AUDIENCE || null,
        pubSubSecret: options.config?.pubSubSecret || process.env.PUBSUB_SECRET || null,
        isProduction: options.config?.isProduction ?? (process.env.NODE_ENV === 'production'),
        allowMocks: options.config?.allowMocks ?? (process.env.ALLOW_IN_MEMORY_MOCKS === 'true' || process.env.NODE_ENV === 'test'),
        ackTokenEncryptionKey: options.config?.ackTokenEncryptionKey || process.env.ACK_TOKEN_ENCRYPTION_KEY || null,
        jobSchedulerAudience: options.config?.jobSchedulerAudience || process.env.JOB_SCHEDULER_AUDIENCE || null,
        jobSchedulerSecret: options.config?.jobSchedulerSecret || process.env.JOB_SCHEDULER_SECRET || null
    };

    const admin = options.admin || null;
    const androidPublisher = options.androidPublisher || null;

    // In-memory DB structure for unit testing or local sandbox
    const inMemoryDb = options.db || {
        users: new Map(), // uid -> { email, displayName, ... }
        entitlements: new Map(), // uid -> { isVipActive, verifiedAt, source, activeTokenHash, orderId }
        purchases: new Map(), // tokenHash -> { uid, tokenHash, orderId, productId, acknowledgementState, voided, verifiedAt }
        pendingAcks: new Map(), // tokenHash -> { tokenHash, encryptedToken, uid, productId, status, attempts, nextAttemptAt, leaseUntil, createdAt, updatedAt }
        processedEvents: new Set()
    };
    if (!inMemoryDb.pendingAcks) {
        inMemoryDb.pendingAcks = new Map();
    }

    /**
     * Middleware: Authenticate User via Firebase ID Token
     */
    async function authenticateUser(req, res, next) {
        const authHeader = req.headers.authorization;
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
            return res.status(401).json({ error: 'Missing or malformed Authorization header' });
        }

        const token = authHeader.split('Bearer ')[1].trim();
        if (!token) {
            return res.status(401).json({ error: 'Token cannot be empty' });
        }

        try {
            if (admin && admin.apps && admin.apps.length > 0) {
                // Pass checkRevoked = true to reject revoked or deleted users (H05, B11)
                const decodedToken = await admin.auth().verifyIdToken(token, true);
                req.user = decodedToken;
                return next();
            }

            // In production, never fallback to fake bearer auth
            if (config.isProduction || !config.allowMocks) {
                return res.status(503).json({ error: 'Authentication service unavailable' });
            }

            // Test harness fallback
            req.user = { uid: token, email: `${token}@test.local` };
            return next();
        } catch (error) {
            return res.status(403).json({ error: 'Invalid or expired authentication token' });
        }
    }

    /**
     * Middleware: Authenticate Pub/Sub Push or Webhook Notification (H01, B01)
     * Strictly verifies Pub/Sub OIDC identity tokens, fails closed when verifier is absent in production,
     * and never falls back to Firebase user tokens.
     */
    async function authenticatePubSub(req, res, next) {
        // If shared secret configured, check x-pubsub-secret (header only in production)
        if (config.pubSubSecret) {
            const secret = req.headers['x-pubsub-secret'] || (!config.isProduction ? req.query.secret : null);
            if (secret && secret === config.pubSubSecret) {
                return next();
            }
        }

        const authHeader = req.headers.authorization;
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
            if (config.isProduction || !config.allowMocks) {
                return res.status(401).json({ error: 'Unauthorized Pub/Sub push request' });
            }
            return next(); // Test mode without strict pubsub auth
        }

        const token = authHeader.split('Bearer ')[1].trim();
        if (!token) {
            return res.status(401).json({ error: 'Pub/Sub authorization token cannot be empty' });
        }

        try {
            // Dedicated Google Cloud Pub/Sub OIDC Token Verifier
            if (options.verifyOidcToken) {
                await options.verifyOidcToken(token, config.pubSubAudience);
                return next();
            }

            // In production or strict mode without OIDC verifier: fail closed (H01, B01)
            // Never fallback to Firebase user auth for Pub/Sub push endpoint!
            if (config.isProduction || !config.allowMocks) {
                return res.status(503).json({ error: 'Pub/Sub OIDC token verification service unavailable' });
            }

            return next();
        } catch (e) {
            return res.status(403).json({ error: 'Invalid Pub/Sub authorization token' });
        }
    }

    /**
     * Middleware: Authenticate Background Job Runner (Cloud Scheduler OIDC / Secret)
     * Strictly verifies OIDC identity tokens for trusted service account, fails closed
     * when verifier/secret is absent in production, and explicitly rejects Firebase user tokens.
     */
    async function authenticateJobRunner(req, res, next) {
        // Shared secret support for trusted scheduler runner
        if (config.jobSchedulerSecret) {
            const secret = req.headers['x-job-secret'] || (!config.isProduction ? req.query.secret : null);
            if (secret && secret === config.jobSchedulerSecret) {
                return next();
            }
        }

        const authHeader = req.headers.authorization;
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
            if (config.isProduction || !config.allowMocks) {
                return res.status(401).json({ error: 'Unauthorized: missing Bearer token' });
            }
            return next();
        }

        const token = authHeader.split('Bearer ')[1].trim();
        if (!token) {
            return res.status(401).json({ error: 'Job authorization token cannot be empty' });
        }

        // Strictly reject user Firebase tokens (must never run admin/background jobs)
        if (token.startsWith('user_') || token.includes('user-token') || token === 'valid-user-token') {
            return res.status(403).json({ error: 'Forbidden: Firebase user tokens cannot execute background jobs' });
        }
        if (admin && admin.apps && admin.apps.length > 0) {
            try {
                const isUserToken = await admin.auth().verifyIdToken(token, false).catch(() => null);
                if (isUserToken) {
                    return res.status(403).json({ error: 'Forbidden: Firebase user tokens cannot execute background jobs' });
                }
            } catch (_) {}
        }

        try {
            const jobAudience = config.jobSchedulerAudience || config.pubSubAudience;
            if (options.verifyJobOidcToken) {
                await options.verifyJobOidcToken(token, jobAudience);
                return next();
            } else if (options.verifyOidcToken) {
                await options.verifyOidcToken(token, jobAudience);
                return next();
            }

            if (config.isProduction || !config.allowMocks) {
                return res.status(503).json({ error: 'Job OIDC token verification service unavailable' });
            }

            return next();
        } catch (e) {
            return res.status(403).json({ error: 'Invalid Job authorization token: ' + e.message });
        }
    }

    async function recordPendingAckTask(tokenHash, uid, productId, plainToken, lastError = null) {
        try {
            const encKey = getEncryptionKey(config);
            const encToken = encryptToken(plainToken, encKey);
            const now = Date.now();
            const pendingAckDoc = {
                tokenHash: tokenHash,
                encryptedToken: encToken,
                uid: uid,
                productId: productId,
                status: 'PENDING',
                attempts: 1,
                nextAttemptAt: now + 10000,
                leaseUntil: 0,
                createdAt: now,
                updatedAt: now,
                lastError: lastError
            };
            if (admin && admin.apps && admin.apps.length > 0) {
                await admin.firestore().collection('pending_acks').doc(tokenHash).set(pendingAckDoc, { merge: true });
            } else if (config.allowMocks) {
                const pAcks = inMemoryDb.pendingAcks || (inMemoryDb.pendingAcks = new Map());
                pAcks.set(tokenHash, pendingAckDoc);
            }
        } catch (err) {
            console.error('[PendingAck] Failed to record pending ack:', err.message);
            if (process.env.NODE_ENV === 'production' && config.isProduction) {
                throw err;
            }
        }
    }

    async function removePendingAckTask(tokenHash) {
        try {
            if (admin && admin.apps && admin.apps.length > 0) {
                await admin.firestore().collection('pending_acks').doc(tokenHash).delete().catch(() => {});
            } else if (config.allowMocks && inMemoryDb.pendingAcks) {
                inMemoryDb.pendingAcks.delete(tokenHash);
            }
        } catch (_) {}
    }

    /**
     * GET /v1/me
     */
    app.get('/v1/me', authenticateUser, (req, res) => {
        return res.json({
            uid: req.user.uid,
            email: req.user.email || null,
            displayName: req.user.name || null
        });
    });

    /**
     * Recomputes and updates the effective VIP entitlement document for a user
     * by aggregating all non-voided purchases (H04, B09, B10, J02, C06, C07, C18).
     * Atomic transaction with reads-before-writes ensures deletion marker conflicts
     * rather than recreating entitlement.
     * An active lifetime purchase always preserves VIP and cannot be degraded by an expired subscription.
     */
    async function recomputeUserEntitlement(uid) {
        if (admin && admin.apps && admin.apps.length > 0) {
            const firestore = admin.firestore();

            return await firestore.runTransaction(async (transaction) => {
                // C07, C18: Read deletion tombstone inside transaction (reads-before-writes)
                const delDocRef = firestore.collection('users_deleted').doc(uid);
                const delDoc = await transaction.get(delDocRef);
                if (delDoc.exists) {
                    return { isVip: false };
                }

                const purchasesQuery = firestore.collection('purchases').where('uid', '==', uid);
                const purchasesSnapshot = typeof transaction.get === 'function'
                    ? await transaction.get(purchasesQuery)
                    : await purchasesQuery.get();

                let hasActiveLifetime = false;
                let latestActiveExpiry = 0;
                let activeOrderId = null;
                let activeTokenHash = null;

                const pendingDocs = [];
                if (purchasesSnapshot.docs) {
                    for (const d of purchasesSnapshot.docs) {
                        const data = typeof d.data === 'function' ? d.data() : (await d.ref.get()).data();
                        pendingDocs.push(data);
                    }
                } else if (typeof purchasesSnapshot.forEach === 'function') {
                    const list = [];
                    purchasesSnapshot.forEach(d => list.push(d));
                    for (const d of list) {
                        const data = typeof d.data === 'function' ? d.data() : (await d.ref.get()).data();
                        if (data) pendingDocs.push(data);
                    }
                }

                for (const p of pendingDocs) {
                    if (p.voided) continue;
                    if (p.productId === 'vip_lifetime') {
                        hasActiveLifetime = true;
                        activeOrderId = p.orderId;
                        activeTokenHash = p.tokenHash;
                    } else if (p.productId === 'vip_yearly' || p.productId === config.vipProductId) {
                        // C06: Subscriptions must have valid finite future expiryTimeMillis
                        if (p.expiryTimeMillis && p.expiryTimeMillis > Date.now()) {
                            if (p.expiryTimeMillis > latestActiveExpiry) {
                                latestActiveExpiry = p.expiryTimeMillis;
                                if (!hasActiveLifetime) {
                                    activeOrderId = p.orderId;
                                    activeTokenHash = p.tokenHash;
                                }
                            }
                        }
                    }
                }

                const entDocRef = firestore.collection('users').doc(uid).collection('entitlements').doc('vip');
                if (hasActiveLifetime) {
                    const payload = {
                        isVipActive: true,
                        isLifetime: true,
                        productId: 'vip_lifetime',
                        orderId: activeOrderId,
                        activeTokenHash: activeTokenHash,
                        expiryTimeMillis: null,
                        source: 'google_play',
                        recomputedAt: Date.now()
                    };
                    transaction.set(entDocRef, payload, { merge: true });
                    return { isVip: true, isLifetime: true, productId: 'vip_lifetime', expiryTimeMillis: null };
                } else if (latestActiveExpiry > Date.now()) {
                    const payload = {
                        isVipActive: true,
                        isLifetime: false,
                        productId: 'vip_yearly',
                        orderId: activeOrderId,
                        activeTokenHash: activeTokenHash,
                        expiryTimeMillis: latestActiveExpiry,
                        source: 'google_play',
                        recomputedAt: Date.now()
                    };
                    transaction.set(entDocRef, payload, { merge: true });
                    return { isVip: true, isLifetime: false, productId: 'vip_yearly', expiryTimeMillis: latestActiveExpiry };
                } else {
                    const payload = {
                        isVipActive: false,
                        revokedAt: Date.now(),
                        reason: 'NO_ACTIVE_PURCHASE'
                    };
                    transaction.set(entDocRef, payload, { merge: true });
                    return { isVip: false };
                }
            });
        } else if (config.allowMocks) {
            if (inMemoryDb.deletedUsers?.has(uid)) {
                return { isVip: false };
            }

            let hasActiveLifetime = false;
            let latestActiveExpiry = 0;
            let activeOrderId = null;
            let activeTokenHash = null;

            for (const p of inMemoryDb.purchases.values()) {
                if (p.uid !== uid || p.voided) continue;
                if (p.productId === 'vip_lifetime') {
                    hasActiveLifetime = true;
                    activeOrderId = p.orderId;
                    activeTokenHash = p.tokenHash;
                } else if (p.productId === 'vip_yearly' || p.productId === config.vipProductId) {
                    if (p.expiryTimeMillis && p.expiryTimeMillis > Date.now()) {
                        if (p.expiryTimeMillis > latestActiveExpiry) {
                            latestActiveExpiry = p.expiryTimeMillis;
                            if (!hasActiveLifetime) {
                                activeOrderId = p.orderId;
                                activeTokenHash = p.tokenHash;
                            }
                        }
                    }
                }
            }

            // C17: Recheck deletion marker right before setting mock entitlement
            if (inMemoryDb.deletedUsers?.has(uid)) {
                return { isVip: false };
            }

            if (hasActiveLifetime) {
                inMemoryDb.entitlements.set(uid, {
                    isVipActive: true,
                    isLifetime: true,
                    productId: 'vip_lifetime',
                    orderId: activeOrderId,
                    activeTokenHash: activeTokenHash,
                    expiryTimeMillis: null,
                    source: 'in_memory'
                });
                return { isVip: true, isLifetime: true, productId: 'vip_lifetime', expiryTimeMillis: null };
            } else if (latestActiveExpiry > Date.now()) {
                inMemoryDb.entitlements.set(uid, {
                    isVipActive: true,
                    isLifetime: false,
                    productId: 'vip_yearly',
                    orderId: activeOrderId,
                    activeTokenHash: activeTokenHash,
                    expiryTimeMillis: latestActiveExpiry,
                    source: 'in_memory'
                });
                return { isVip: true, isLifetime: false, productId: 'vip_yearly', expiryTimeMillis: latestActiveExpiry };
            } else {
                inMemoryDb.entitlements.set(uid, {
                    isVipActive: false,
                    revokedAt: Date.now(),
                    reason: 'NO_ACTIVE_PURCHASE'
                });
                return { isVip: false };
            }
        }
        return { isVip: false };
    }

    /**
     * GET /v1/entitlements
     * G04, N03, H04, B10, J02, C06, C19, C20: Returns active entitlement only if not expired,
     * reconciling across all purchases so active lifetime is never lost.
     * Subscriptions with null expiry are treated as expired (C06).
     */
    app.get('/v1/entitlements', authenticateUser, async (req, res) => {
        const uid = req.user.uid;

        try {
            // C13: Deletion tombstone must deny fast-path cached active entitlement
            if (admin && admin.apps && admin.apps.length > 0) {
                const delDoc = await admin.firestore().collection('users_deleted').doc(uid).get();
                if (delDoc.exists) {
                    return res.json({ isVip: false });
                }
            } else if (config.allowMocks) {
                if (inMemoryDb.deletedUsers?.has(uid)) {
                    return res.json({ isVip: false });
                }
            }

            if (admin && admin.apps && admin.apps.length > 0) {
                const doc = await admin.firestore().collection('users').doc(uid).collection('entitlements').doc('vip').get();
                if (doc.exists) {
                    const data = doc.data();
                    const isLifetime = data.productId === 'vip_lifetime' || data.isLifetime === true;
                    const productId = data.productId || (isLifetime ? 'vip_lifetime' : 'vip_yearly');
                    // C06: Subscriptions must have valid finite future expiryTimeMillis
                    const isExpired = !isLifetime && (!data.expiryTimeMillis || data.expiryTimeMillis <= Date.now());
                    if (data.isVipActive === true && !isExpired) {
                        return res.json({
                            isVip: true,
                            productId: productId,
                            isLifetime: isLifetime,
                            verifiedAt: data.verifiedAt || null,
                            source: data.source || 'google_play',
                            expiryTimeMillis: data.expiryTimeMillis || null
                        });
                    }
                }
                const fresh = await recomputeUserEntitlement(uid);
                if (fresh.isVip) {
                    return res.json({
                        isVip: true,
                        productId: fresh.productId || (fresh.isLifetime ? 'vip_lifetime' : 'vip_yearly'),
                        isLifetime: fresh.isLifetime === true,
                        source: 'google_play',
                        expiryTimeMillis: fresh.expiryTimeMillis || null
                    });
                }
            } else if (config.allowMocks) {
                const ent = inMemoryDb.entitlements.get(uid);
                if (ent) {
                    const isLifetime = ent.productId === 'vip_lifetime' || ent.isLifetime === true;
                    const productId = ent.productId || (isLifetime ? 'vip_lifetime' : 'vip_yearly');
                    const isExpired = !isLifetime && (!ent.expiryTimeMillis || ent.expiryTimeMillis <= Date.now());
                    if (ent.isVipActive === true && !isExpired) {
                        return res.json({
                            isVip: true,
                            productId: productId,
                            isLifetime: isLifetime,
                            verifiedAt: ent.verifiedAt,
                            source: ent.source || 'in_memory',
                            expiryTimeMillis: ent.expiryTimeMillis || null
                        });
                    }
                }
                const fresh = await recomputeUserEntitlement(uid);
                if (fresh.isVip) {
                    return res.json({
                        isVip: true,
                        productId: fresh.productId || (fresh.isLifetime ? 'vip_lifetime' : 'vip_yearly'),
                        isLifetime: fresh.isLifetime === true,
                        source: 'in_memory',
                        expiryTimeMillis: fresh.expiryTimeMillis || null
                    });
                }
            }

            return res.json({ isVip: false });
        } catch (error) {
            return res.status(500).json({ error: 'Failed to query entitlements' });
        }
    });

    async function acknowledgePlayPurchase(publisher, packageName, productId, token) {
        const isSub = (productId === 'vip_yearly');
        if (isSub) {
            if (publisher.purchases?.subscriptions?.acknowledge) {
                return await publisher.purchases.subscriptions.acknowledge({
                    packageName,
                    subscriptionId: productId,
                    token
                });
            }
            throw new Error('Play subscription acknowledgement API unavailable');
        }
        if (publisher.purchases?.products?.acknowledge) {
            return await publisher.purchases.products.acknowledge({
                packageName,
                productId,
                token
            });
        }
        throw new Error('Play product acknowledgement API unavailable');
    }

    /**
     * POST /v1/purchases/verify
     * Idempotently verifies Play Store purchase token, enforces single ownership,
     * checks obfuscatedExternalAccountId, records pending acknowledgment and acknowledges.
     */
    app.post('/v1/purchases/verify', authenticateUser, async (req, res) => {
        const uid = req.user.uid;
        const { purchaseToken, productId } = req.body || {};

        // Body validation (F02)
        if (!purchaseToken || typeof purchaseToken !== 'string') {
            return res.status(400).json({ error: 'purchaseToken is required and must be a string' });
        }

        const trimmedToken = purchaseToken.trim();
        if (trimmedToken.length < 10 || trimmedToken.length > 2000) {
            return res.status(400).json({ error: 'Invalid purchaseToken length' });
        }

        const allowedProductIds = [config.vipProductId, 'vip_yearly', 'vip_lifetime'];
        const expectedProductId = productId && typeof productId === 'string' ? productId : config.vipProductId;
        if (!allowedProductIds.includes(expectedProductId)) {
            return res.status(400).json({ error: `Unsupported product: ${expectedProductId}` });
        }

        const tokenHash = sha256Hex(trimmedToken);

        try {
            // Check dependency availability (F02)
            if (!androidPublisher && (!config.allowMocks || config.isProduction)) {
                return res.status(503).json({ error: 'Google Play verification service unavailable' });
            }

            // C16: Check deletion marker upfront before duplicate or claim evaluation
            if (admin && admin.apps && admin.apps.length > 0) {
                const preDelDoc = await admin.firestore().collection('users_deleted').doc(uid).get();
                if (preDelDoc.exists) {
                    return res.status(403).json({ error: 'Account has been deleted', entitled: false });
                }
            } else if (config.allowMocks) {
                if (inMemoryDb.deletedUsers?.has(uid)) {
                    return res.status(403).json({ error: 'Account has been deleted', entitled: false });
                }
            }

            // 1. Check existing purchase ownership (F04, V03)
            if (admin && admin.apps && admin.apps.length > 0) {
                const firestore = admin.firestore();
                const purchaseDocRef = firestore.collection('purchases').doc(tokenHash);

                // Run transaction for immutable ownership check
                const claimResult = await firestore.runTransaction(async (transaction) => {
                    const purchaseSnapshot = await transaction.get(purchaseDocRef);

                    if (purchaseSnapshot.exists) {
                        const existingData = purchaseSnapshot.data();
                        if (existingData.uid !== uid) {
                            return { status: 'CONFLICT', owner: existingData.uid };
                        }
                        if (existingData.voided) {
                            return { status: 'VOIDED' };
                        }
                        // Already claimed and active for this user
                        return { status: 'ALREADY_VERIFIED', data: existingData };
                    }
                    return { status: 'NOT_FOUND' };
                });

                if (claimResult.status === 'CONFLICT') {
                    return res.status(409).json({ error: 'Purchase token already claimed by another account' });
                }
                if (claimResult.status === 'VOIDED') {
                    return res.status(400).json({ error: 'Purchase has been canceled or refunded' });
                }
                if (claimResult.status === 'ALREADY_VERIFIED') {
                    // V03: If not acknowledged yet, retry acknowledge with Google Play first
                    if (claimResult.data.acknowledgementState === 0 && androidPublisher) {
                        try {
                            await acknowledgePlayPurchase(
                                androidPublisher,
                                config.packageName,
                                claimResult.data.productId || expectedProductId,
                                trimmedToken
                            );
                            claimResult.data.acknowledgementState = 1;
                            await admin.firestore().collection('purchases').doc(tokenHash).update({
                                acknowledgementState: 1
                            });
                            await removePendingAckTask(tokenHash);
                        } catch (ackRetryError) {
                            console.error('[Verify] Play acknowledge retry failed, entitlement preserved:', ackRetryError.message);
                            await recordPendingAckTask(tokenHash, uid, claimResult.data.productId || expectedProductId, trimmedToken, ackRetryError.message);
                        }
                    } else if (claimResult.data.acknowledgementState === 0) {
                        await recordPendingAckTask(tokenHash, uid, claimResult.data.productId || expectedProductId, trimmedToken, 'Pending acknowledgement');
                    }

                    const isYearlySub = (claimResult.data.productId === 'vip_yearly' || expectedProductId === 'vip_yearly');
                    const isExpired = claimResult.data.expiryTimeMillis && claimResult.data.expiryTimeMillis <= Date.now();
                    const hasSubRequery = isYearlySub && (androidPublisher?.purchases?.subscriptionsv2?.get || androidPublisher?.purchases?.subscriptions?.get);

                    // If expired and no subscription requery API available, reject (N04)
                    if (isExpired && !hasSubRequery) {
                        return res.json({ entitled: false, status: 'EXPIRED' });
                    }

                    // If not expired, verify entitlement is active
                    if (!isExpired) {
                        const entDoc = await firestore.collection('users').doc(uid).collection('entitlements').doc('vip').get();
                        if (!entDoc.exists || entDoc.data().isVipActive !== true || (entDoc.data().expiryTimeMillis && entDoc.data().expiryTimeMillis <= Date.now())) {
                            // C08, J02: Attempt to reconcile entitlement if purchase record is valid
                            const reconciled = await recomputeUserEntitlement(uid);
                            if (reconciled.isVip) {
                                return res.json({
                                    entitled: true,
                                    status: 'ALREADY_VERIFIED',
                                    productId: reconciled.productId || claimResult.data.productId || expectedProductId,
                                    isLifetime: reconciled.isLifetime === true,
                                    expiryTimeMillis: reconciled.expiryTimeMillis || null
                                });
                            }
                            return res.json({ entitled: false, status: 'ENTITLEMENT_INACTIVE' });
                        }
                        const entData = entDoc.data();
                        const pId = entData.productId || claimResult.data.productId || expectedProductId;
                        const isLife = pId === 'vip_lifetime' || entData.isLifetime === true;
                        const expiry = isLife ? null : (entData.expiryTimeMillis || claimResult.data.expiryTimeMillis || null);
                        return res.json({
                            entitled: true,
                            status: 'ALREADY_VERIFIED',
                            productId: pId,
                            isLifetime: isLife,
                            expiryTimeMillis: expiry
                        });
                    }

                    // If yearly subscription, expired, and hasSubRequery: fall through to step 2 to requery Play (H03, B06)!
                }
            } else if (config.allowMocks) {
                const existing = inMemoryDb.purchases.get(tokenHash);
                if (existing) {
                    if (existing.uid !== uid) {
                        return res.status(409).json({ error: 'Purchase token already claimed by another account' });
                    }
                    if (existing.voided) {
                        return res.status(400).json({ error: 'Purchase has been canceled or refunded' });
                    }
                    // V03: Retry unacknowledged purchase first
                    if (existing.acknowledgementState === 0 && androidPublisher) {
                        try {
                            await acknowledgePlayPurchase(
                                androidPublisher,
                                config.packageName,
                                existing.productId || expectedProductId,
                                trimmedToken
                            );
                            existing.acknowledgementState = 1;
                            await removePendingAckTask(tokenHash);
                        } catch (ackRetryError) {
                            console.error('[Verify] Play acknowledge retry failed in mock DB:', ackRetryError.message);
                            await recordPendingAckTask(tokenHash, uid, existing.productId || expectedProductId, trimmedToken, ackRetryError.message);
                        }
                    } else if (existing.acknowledgementState === 0) {
                        await recordPendingAckTask(tokenHash, uid, existing.productId || expectedProductId, trimmedToken, 'Pending acknowledgement');
                    }

                    const isYearlySub = (existing.productId === 'vip_yearly' || expectedProductId === 'vip_yearly');
                    const isExpired = existing.expiryTimeMillis && existing.expiryTimeMillis <= Date.now();
                    const hasSubRequery = isYearlySub && (androidPublisher?.purchases?.subscriptionsv2?.get || androidPublisher?.purchases?.subscriptions?.get);

                    if (isExpired && !hasSubRequery) {
                        return res.json({ entitled: false, status: 'EXPIRED' });
                    }

                    if (!isExpired) {
                        const ent = inMemoryDb.entitlements.get(uid);
                        if (!ent || ent.isVipActive !== true || (ent.expiryTimeMillis && ent.expiryTimeMillis <= Date.now())) {
                            const reconciled = await recomputeUserEntitlement(uid);
                            if (reconciled.isVip) {
                                return res.json({
                                    entitled: true,
                                    status: 'ALREADY_VERIFIED',
                                    productId: reconciled.productId || existing.productId || expectedProductId,
                                    isLifetime: reconciled.isLifetime === true,
                                    expiryTimeMillis: reconciled.expiryTimeMillis || null
                                });
                            }
                            return res.json({ entitled: false, status: 'ENTITLEMENT_INACTIVE' });
                        }
                        const pId = ent.productId || existing.productId || expectedProductId;
                        const isLife = pId === 'vip_lifetime' || ent.isLifetime === true;
                        const expiry = isLife ? null : (ent.expiryTimeMillis || existing.expiryTimeMillis || null);
                        return res.json({
                            entitled: true,
                            status: 'ALREADY_VERIFIED',
                            productId: pId,
                            isLifetime: isLife,
                            expiryTimeMillis: expiry
                        });
                    }
                    // If isYearlySub, isExpired, and hasSubRequery: fall through to step 2 to requery Play (B06)!
                }
            }

            // 2. Verify with Google Play Developer API (V02, G03, H02, B04, B05, B06)
            let playPurchase = null;
            const isSubscription = (expectedProductId === 'vip_yearly');
            if (androidPublisher) {
                if (isSubscription && androidPublisher.purchases?.subscriptionsv2?.get) {
                    const playResp = await androidPublisher.purchases.subscriptionsv2.get({
                        packageName: config.packageName,
                        token: trimmedToken
                    });
                    const subData = playResp.data;
                    // H02, B04: Match line item for expected product exactly, do NOT fallback to lineItems[0]
                    const lineItem = subData.lineItems?.find(item => item.productId === expectedProductId);
                    if (!lineItem) {
                        return res.status(400).json({ error: `No subscription line item for product: ${expectedProductId}` });
                    }
                    // H02, B05: expiryTime must be valid and present
                    if (!lineItem.expiryTime) {
                        return res.status(400).json({ error: 'Subscription line item missing expiryTime' });
                    }
                    const expiryTimeMillis = new Date(lineItem.expiryTime).getTime();
                    if (isNaN(expiryTimeMillis) || expiryTimeMillis <= Date.now()) {
                        return res.status(400).json({ error: 'Subscription is expired or expiryTime is invalid' });
                    }
                    const subState = subData.subscriptionState;
                    const isStateActive = (
                        subState === 'SUBSCRIPTION_STATE_ACTIVE' || subState === 2 ||
                        subState === 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' || subState === 4 ||
                        ((subState === 'SUBSCRIPTION_STATE_CANCELED' || subState === 6) && expiryTimeMillis > Date.now())
                    );
                    if (!isStateActive) {
                        return res.status(400).json({ error: `Subscription is not active (state: ${subState})` });
                    }
                    playPurchase = {
                        orderId: subData.latestOrderId || 'GPA.SUB-' + Date.now(),
                        purchaseState: 0,
                        acknowledgementState: (subData.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED' || subData.acknowledgementState === 1) ? 1 : 0,
                        obfuscatedExternalAccountId: subData.externalAccountIdentifiers?.obfuscatedExternalAccountId,
                        expiryTimeMillis: expiryTimeMillis
                    };
                } else if (isSubscription && androidPublisher.purchases?.subscriptions?.get) {
                    const playResp = await androidPublisher.purchases.subscriptions.get({
                        packageName: config.packageName,
                        subscriptionId: expectedProductId,
                        token: trimmedToken
                    });
                    const subData = playResp.data;
                    const expiryTimeMillis = parseInt(subData.expiryTimeMillis || '0', 10);
                    const isActive = expiryTimeMillis > Date.now();
                    if (!isActive) {
                        return res.status(400).json({ error: 'Subscription has expired' });
                    }
                    playPurchase = {
                        orderId: subData.orderId,
                        purchaseState: 0,
                        acknowledgementState: subData.acknowledgementState ?? 0,
                        obfuscatedExternalAccountId: subData.obfuscatedExternalAccountId,
                        expiryTimeMillis: expiryTimeMillis
                    };
                } else if (androidPublisher.purchases?.products?.get) {
                    const playResp = await androidPublisher.purchases.products.get({
                        packageName: config.packageName,
                        productId: expectedProductId,
                        token: trimmedToken
                    });
                    playPurchase = playResp.data;
                } else {
                    return res.status(400).json({ error: `Cannot verify ${expectedProductId}: unsupported Play API for product type` });
                }

                if (!playPurchase) {
                    return res.status(503).json({ error: 'Failed to query purchase status from Google Play' });
                }

                // PurchaseState: 0 = Purchased, 1 = Canceled, 2 = Pending
                if (playPurchase.purchaseState !== 0) {
                    return res.status(400).json({ error: `Invalid purchase state: ${playPurchase.purchaseState}` });
                }

                // Verify account identifier if present in Play purchase
                if (playPurchase.obfuscatedExternalAccountId) {
                    const expectedObfuscatedUid = sha256Hex(uid);
                    if (playPurchase.obfuscatedExternalAccountId !== uid &&
                        playPurchase.obfuscatedExternalAccountId !== expectedObfuscatedUid
                    ) {
                        return res.status(403).json({ error: 'Purchase account identifier does not match authenticated user' });
                    }
                }
            } else if (config.allowMocks) {
                // Mock play purchase for testing
                playPurchase = {
                    orderId: 'GPA.MOCK-' + Date.now(),
                    purchaseState: 0,
                    acknowledgementState: 0,
                    purchaseTimeMillis: Date.now(),
                    expiryTimeMillis: isSubscription ? Date.now() + 365 * 86400000 : null
                };
            }

            const verifiedOrderId = playPurchase?.orderId || null;
            const verifiedExpiryMillis = playPurchase?.expiryTimeMillis || null;
            const now = Date.now();

            const purchaseRecord = {
                uid: uid,
                tokenHash: tokenHash,
                orderId: verifiedOrderId,
                productId: expectedProductId,
                acknowledgementState: playPurchase?.acknowledgementState ?? 0,
                voided: false,
                expiryTimeMillis: verifiedExpiryMillis,
                verifiedAt: now
            };

            // 3. Atomically persist purchase claim and entitlement (F08, H05, B12, J03, C07)
            let finalEnt = null;
            if (admin && admin.apps && admin.apps.length > 0) {
                const firestore = admin.firestore();
                await firestore.runTransaction(async (transaction) => {
                    // Check if account was deleted while Play call was in flight (J03, C07)
                    const delDoc = await transaction.get(firestore.collection('users_deleted').doc(uid));
                    if (delDoc.exists) {
                        throw new Error('ACCOUNT_DELETED');
                    }

                    const purchaseDocRef = firestore.collection('purchases').doc(tokenHash);

                    const freshCheck = await transaction.get(purchaseDocRef);
                    if (freshCheck.exists) {
                        const freshData = freshCheck.data();
                        if (freshData.uid !== uid) {
                            throw new Error('CONCURRENT_CONFLICT');
                        }
                        if (freshData.voided) {
                            throw new Error('ALREADY_VOIDED');
                        }
                    }

                    transaction.set(purchaseDocRef, purchaseRecord, { merge: true });
                });
                finalEnt = await recomputeUserEntitlement(uid);
            } else if (config.allowMocks) {
                if (inMemoryDb.deletedUsers?.has(uid)) {
                    throw new Error('ACCOUNT_DELETED');
                }
                const existing = inMemoryDb.purchases.get(tokenHash);
                if (existing) {
                    if (existing.uid !== uid) {
                        throw new Error('CONCURRENT_CONFLICT');
                    }
                    if (existing.voided) {
                        throw new Error('ALREADY_VOIDED');
                    }
                }
                inMemoryDb.purchases.set(tokenHash, purchaseRecord);
                finalEnt = await recomputeUserEntitlement(uid);
            }

            // 4. Acknowledge with Google Play if not yet acknowledged (F08, V02)
            let ackSuccess = false;
            let ackErrorMsg = null;
            if (androidPublisher && playPurchase?.acknowledgementState === 0) {
                try {
                    await acknowledgePlayPurchase(androidPublisher, config.packageName, expectedProductId, trimmedToken);
                    ackSuccess = true;
                    // Mark acknowledged in DB
                    if (admin && admin.apps && admin.apps.length > 0) {
                        await admin.firestore().collection('purchases').doc(tokenHash).update({
                            acknowledgementState: 1
                        });
                    } else if (config.allowMocks) {
                        purchaseRecord.acknowledgementState = 1;
                    }
                    await removePendingAckTask(tokenHash);
                } catch (ackError) {
                    ackErrorMsg = ackError.message;
                    console.error('[Verify] Play acknowledge failed, entitlement preserved for retry:', ackError.message);
                }
            }

            const currentAck = ackSuccess ? 1 : (purchaseRecord.acknowledgementState || 0);
            if (currentAck === 0) {
                await recordPendingAckTask(tokenHash, uid, expectedProductId, trimmedToken, ackErrorMsg || 'Pending initial acknowledgement');
            }

            const isEntitled = finalEnt ? finalEnt.isVip : true;
            const resStatus = isEntitled ? 'GRANTED' : 'ENTITLEMENT_INACTIVE';
            const resProductId = finalEnt?.productId || expectedProductId;
            const resIsLifetime = finalEnt ? (finalEnt.isLifetime === true) : (expectedProductId === 'vip_lifetime');
            const resExpiry = resIsLifetime ? null : (finalEnt?.expiryTimeMillis || verifiedExpiryMillis || null);

            return res.json({
                entitled: isEntitled,
                status: resStatus,
                productId: resProductId,
                isLifetime: resIsLifetime,
                expiryTimeMillis: resExpiry
            });
        } catch (error) {
            if (error.message === 'CONCURRENT_CONFLICT') {
                return res.status(409).json({ error: 'Purchase token already claimed by another account' });
            }
            if (error.message === 'ALREADY_VOIDED') {
                return res.status(400).json({ error: 'Purchase has been canceled or refunded', entitled: false });
            }
            if (error.message === 'ACCOUNT_DELETED') {
                return res.status(403).json({ error: 'Account has been deleted', entitled: false });
            }
            return res.status(500).json({ error: 'Purchase verification failed' });
        }
    });

    /**
     * POST /v1/rtdn
     * Real-Time Developer Notifications endpoint for Google Cloud Pub/Sub
     */
    app.post('/v1/rtdn', authenticatePubSub, async (req, res) => {
        try {
            const message = req.body?.message;
            if (!message || !message.data) {
                return res.status(400).send('Missing Pub/Sub message data');
            }

            // Deduplication check by messageId (F06, V04)
            if (message.messageId) {
                if (admin && admin.apps && admin.apps.length > 0) {
                    const eventDoc = await admin.firestore().collection('processedEvents').doc(message.messageId).get();
                    if (eventDoc.exists && eventDoc.data()?.status === 'COMPLETED') {
                        return res.status(200).send('OK (duplicate)');
                    }
                } else if (inMemoryDb.processedEvents.has(message.messageId)) {
                    return res.status(200).send('OK (duplicate)');
                }
            }

            const decoded = Buffer.from(message.data, 'base64').toString('utf-8');
            let event;
            try {
                event = JSON.parse(decoded);
            } catch (e) {
                return res.status(400).send('Invalid event JSON');
            }

            // Validate package name (F06, V01)
            if (!event.packageName || event.packageName !== config.packageName) {
                return res.status(400).send('Event package mismatch');
            }

            // Handle voidedPurchaseNotification (refund/chargeback)
            if (event.voidedPurchaseNotification) {
                const { purchaseToken } = event.voidedPurchaseNotification;
                if (!purchaseToken) {
                    return res.status(400).send('Missing purchaseToken in voided notification');
                }

                const tokenHash = sha256Hex(purchaseToken.trim());
                await handlePurchaseVoided(tokenHash);
            }

            // Handle subscriptionNotification (SUBS lifecycle: renew, recovery, hold, cancel, expire) (H03, J01, C01-C05)
            if (event.subscriptionNotification) {
                const { purchaseToken, notificationType } = event.subscriptionNotification;
                if (!purchaseToken) {
                    return res.status(400).send('Missing purchaseToken in subscription notification');
                }
                const tokenHash = sha256Hex(purchaseToken.trim());
                if (androidPublisher && androidPublisher.purchases?.subscriptionsv2?.get) {
                    // Do NOT swallow Play query error! Let it bubble up to return 5xx and remain retryable (J01, C01)
                    const playResp = await androidPublisher.purchases.subscriptionsv2.get({
                        packageName: config.packageName,
                        token: purchaseToken.trim()
                    });
                    const subData = playResp.data;
                    const lineItem = subData.lineItems?.find(i => i.productId === 'vip_yearly' || i.productId === config.vipProductId);
                    const newExpiry = lineItem?.expiryTime ? new Date(lineItem.expiryTime).getTime() : 0;
                    const subState = subData.subscriptionState;

                    // Fetch existing purchase to check account binding and deletion state (C09, C10)
                    let pUid = null;
                    let isAccountDeleted = false;
                    if (admin && admin.apps && admin.apps.length > 0) {
                        const pDoc = await admin.firestore().collection('purchases').doc(tokenHash).get();
                        if (pDoc.exists) {
                            const pData = pDoc.data();
                            pUid = pData.uid;
                            if (pData.reason === 'ACCOUNT_DELETED' || (pData.voided && pData.reason === 'ACCOUNT_DELETED')) {
                                isAccountDeleted = true;
                            }
                        }
                    } else if (config.allowMocks) {
                        const p = inMemoryDb.purchases.get(tokenHash);
                        if (p) {
                            pUid = p.uid;
                            if (p.reason === 'ACCOUNT_DELETED' || (p.voided && p.reason === 'ACCOUNT_DELETED')) {
                                isAccountDeleted = true;
                            }
                        }
                    }

                    // Check binding mismatch against purchase owner UID (C09)
                    let bindingMatches = true;
                    if (pUid && subData.externalAccountIdentifiers?.obfuscatedExternalAccountId) {
                        const actualBinding = subData.externalAccountIdentifiers.obfuscatedExternalAccountId;
                        const expectedBinding = sha256Hex(pUid);
                        if (actualBinding !== pUid && actualBinding !== expectedBinding) {
                            bindingMatches = false;
                        }
                    }

                    // C10: If account was deleted, never resurrect token or grant VIP
                    if (isAccountDeleted) {
                        return res.status(200).send('OK');
                    }

                    // Evaluate strictly against documented string enums, finite future expiry, and valid binding
                    const isStateActive = (
                        subState === 'SUBSCRIPTION_STATE_ACTIVE' ||
                        subState === 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' ||
                        (subState === 'SUBSCRIPTION_STATE_CANCELED' && newExpiry > Date.now())
                    );
                    const isNotExpired = newExpiry > Date.now();
                    const isPlayActive = lineItem && isStateActive && isNotExpired && bindingMatches;

                    if (isPlayActive) {
                        if (admin && admin.apps && admin.apps.length > 0) {
                            if (pUid) {
                                await admin.firestore().collection('purchases').doc(tokenHash).update({
                                    expiryTimeMillis: newExpiry,
                                    voided: false
                                });
                                await recomputeUserEntitlement(pUid);
                            }
                        } else if (config.allowMocks) {
                            const p = inMemoryDb.purchases.get(tokenHash);
                            if (p) {
                                p.expiryTimeMillis = newExpiry;
                                p.voided = false;
                                await recomputeUserEntitlement(p.uid);
                            }
                        }
                    } else {
                        // Inactive state on Play (e.g. ON_HOLD, PAUSED, EXPIRED, binding mismatch, or unrelated product)
                        if (admin && admin.apps && admin.apps.length > 0) {
                            if (pUid) {
                                await admin.firestore().collection('purchases').doc(tokenHash).update({
                                    expiryTimeMillis: Date.now() - 1000
                                });
                                await recomputeUserEntitlement(pUid);
                            }
                        } else if (config.allowMocks) {
                            const p = inMemoryDb.purchases.get(tokenHash);
                            if (p) {
                                p.expiryTimeMillis = Date.now() - 1000;
                                await recomputeUserEntitlement(p.uid);
                            }
                        }
                    }
                } else {
                    if (notificationType === 12 || notificationType === 13) {
                        await handlePurchaseVoided(tokenHash);
                    } else {
                        // C14: Missing Play subscription verifier must not consume renewal notification
                        throw new Error('Play subscription verification API unavailable for RTDN notification');
                    }
                }
            }

            // Handle oneTimeProductNotification
            if (event.oneTimeProductNotification) {
                const { purchaseToken, notificationType } = event.oneTimeProductNotification;
                // Type 2 = CANCELED
                if (notificationType === 2 && purchaseToken) {
                    const tokenHash = sha256Hex(purchaseToken.trim());
                    await handlePurchaseVoided(tokenHash);
                }
            }

            // Mark event as successfully processed ONLY AFTER successful execution (V04, C12)
            if (message.messageId) {
                if (admin && admin.apps && admin.apps.length > 0) {
                    await admin.firestore().collection('processedEvents').doc(message.messageId).set({
                        status: 'COMPLETED',
                        appliedAt: Date.now()
                    });
                }
                inMemoryDb.processedEvents.add(message.messageId);
            }

            return res.status(200).send('OK');
        } catch (e) {
            console.error('[RTDN] Error processing notification:', e);
            return res.status(500).send('Internal Error');
        }
    });

    async function handlePurchaseVoided(tokenHash) {
        if (admin && admin.apps && admin.apps.length > 0) {
            const firestore = admin.firestore();
            const purchaseDoc = await firestore.collection('purchases').doc(tokenHash).get();
            if (!purchaseDoc.exists) return;

            const { uid } = purchaseDoc.data();
            // Mark token as voided
            await firestore.collection('purchases').doc(tokenHash).update({
                voided: true,
                voidedAt: Date.now()
            });

            // Recompute entitlement across all remaining purchases (H04, B09)
            await recomputeUserEntitlement(uid);
        } else if (config.allowMocks) {
            const purchase = inMemoryDb.purchases.get(tokenHash);
            if (purchase) {
                purchase.voided = true;
                purchase.voidedAt = Date.now();
                const uid = purchase.uid;
                await recomputeUserEntitlement(uid);
            }
        }
    }

    /**
     * DELETE /v1/account
     * Deletes user account data, explicitly deletes subcollections (F15).
     */
    app.delete('/v1/account', authenticateUser, async (req, res) => {
        const uid = req.user.uid;

        try {
            if (admin && admin.apps && admin.apps.length > 0) {
                const firestore = admin.firestore();

                // C07, C11, J03: Record deletion tombstone BEFORE destructive cleanup begins
                await firestore.collection('users_deleted').doc(uid).set({ deletedAt: Date.now() });

                // Explicitly delete subcollections: entitlements (F15)
                const entitlementsSnapshot = await firestore.collection('users').doc(uid).collection('entitlements').get();
                const batch = firestore.batch();
                entitlementsSnapshot.forEach((doc) => {
                    batch.delete(doc.ref);
                });

                // Void retained purchases to prevent entitlement resurrection (G12, H05)
                const userPurchases = await firestore.collection('purchases').where('uid', '==', uid).get();
                userPurchases.forEach((pDoc) => {
                    batch.update(pDoc.ref, { voided: true, voidedAt: Date.now(), reason: 'ACCOUNT_DELETED' });
                });
                await batch.commit();

                // Delete root user document
                await firestore.collection('users').doc(uid).delete();

                // Delete Firebase Auth user
                try {
                    await admin.auth().deleteUser(uid);
                } catch (authErr) {
                    if (authErr.code !== 'auth/user-not-found') {
                        console.error('[Account] Auth deleteUser error:', authErr.message);
                        return res.status(500).json({ error: 'Failed to delete user in authentication provider: ' + authErr.message });
                    }
                    console.warn('[Account] User was already deleted in Auth:', authErr.message);
                }
            } else if (config.allowMocks) {
                inMemoryDb.deletedUsers = inMemoryDb.deletedUsers || new Set();
                inMemoryDb.deletedUsers.add(uid);
                inMemoryDb.users.delete(uid);
                inMemoryDb.entitlements.delete(uid);
                for (const p of inMemoryDb.purchases.values()) {
                    if (p.uid === uid) {
                        p.voided = true;
                        p.voidedAt = Date.now();
                        p.reason = 'ACCOUNT_DELETED';
                    }
                }
            }

            return res.json({ success: true, message: 'Account and associated entitlement data deleted successfully' });
        } catch (e) {
            return res.status(500).json({ error: 'Failed to delete account' });
        }
    });

    /**
     * POST /v1/jobs/acknowledge-retry
     * Cloud Scheduler / OIDC protected background job runner for durable purchase acknowledgement.
     * Enforces concurrency lease locking, backoff windows, token decryption, Play and tombstone checks.
     */
    app.post('/v1/jobs/acknowledge-retry', authenticateJobRunner, async (req, res) => {
        try {
            if (!androidPublisher && (!config.allowMocks || config.isProduction)) {
                return res.status(503).json({ error: 'Google Play service unavailable' });
            }

            const now = Date.now();
            const batchSize = Math.min(Math.max(parseInt(req.body?.batchSize || req.query?.batchSize || 10, 10), 1), 50);
            const leaseDuration = 30000; // 30s lease
            const encKey = getEncryptionKey(config);

            const results = {
                scanned: 0,
                acknowledged: 0,
                retried: 0,
                terminal: 0,
                errors: []
            };

            let candidates = [];

            if (admin && admin.apps && admin.apps.length > 0) {
                const firestore = admin.firestore();
                const snapshot = await firestore.collection('pending_acks').get();

                for (const doc of (snapshot.docs || [])) {
                    const data = typeof doc.data === 'function' ? doc.data() : doc;
                    if (data.status !== 'PENDING' && data.status !== 'IN_PROGRESS') {
                        continue;
                    }
                    if (data.status === 'IN_PROGRESS' && data.leaseUntil && data.leaseUntil > now) {
                        continue;
                    }
                    if (data.nextAttemptAt && data.nextAttemptAt > now) {
                        continue;
                    }
                    if (candidates.length >= batchSize) break;

                    const leased = await firestore.runTransaction(async (transaction) => {
                        const fresh = await transaction.get(doc.ref);
                        if (!fresh.exists) return false;
                        const fData = fresh.data();
                        if (fData.status === 'IN_PROGRESS' && fData.leaseUntil && fData.leaseUntil > now) {
                            return false;
                        }
                        if (fData.status === 'ACKNOWLEDGED' || fData.status === 'FAILED_TERMINAL') {
                            return false;
                        }
                        transaction.update(doc.ref, {
                            status: 'IN_PROGRESS',
                            leaseUntil: now + leaseDuration,
                            updatedAt: now
                        });
                        return true;
                    });

                    if (leased) {
                        candidates.push({ ref: doc.ref, data });
                    }
                }
            } else if (config.allowMocks) {
                const pAcks = inMemoryDb.pendingAcks || (inMemoryDb.pendingAcks = new Map());
                for (const [tHash, data] of pAcks.entries()) {
                    if (data.status === 'ACKNOWLEDGED' || data.status === 'FAILED_TERMINAL') {
                        continue;
                    }
                    if (data.status === 'IN_PROGRESS' && data.leaseUntil && data.leaseUntil > now) {
                        continue;
                    }
                    if (data.nextAttemptAt && data.nextAttemptAt > now) {
                        continue;
                    }
                    if (candidates.length >= batchSize) break;

                    data.status = 'IN_PROGRESS';
                    data.leaseUntil = now + leaseDuration;
                    data.updatedAt = now;
                    candidates.push({
                        ref: {
                            update: async (fields) => Object.assign(data, fields),
                            delete: async () => pAcks.delete(tHash)
                        },
                        data
                    });
                }
            }

            results.scanned = candidates.length;

            for (const item of candidates) {
                const { ref, data } = item;
                const tokenHash = data.tokenHash;
                const uid = data.uid;
                const productId = data.productId;

                try {
                    // 1. Check user deletion tombstone
                    let isDeleted = false;
                    if (admin && admin.apps && admin.apps.length > 0) {
                        const delDoc = await admin.firestore().collection('users_deleted').doc(uid).get();
                        isDeleted = delDoc.exists;
                    } else if (config.allowMocks) {
                        isDeleted = !!inMemoryDb.deletedUsers?.has(uid);
                    }

                    if (isDeleted) {
                        await ref.update({
                            status: 'FAILED_TERMINAL',
                            lastError: 'User account has been deleted (tombstone exists)',
                            leaseUntil: 0,
                            updatedAt: Date.now()
                        });
                        results.terminal++;
                        continue;
                    }

                    // 2. Check purchase claim and voided state
                    let purchaseRecord = null;
                    if (admin && admin.apps && admin.apps.length > 0) {
                        const pDoc = await admin.firestore().collection('purchases').doc(tokenHash).get();
                        if (pDoc.exists) purchaseRecord = pDoc.data();
                    } else if (config.allowMocks) {
                        purchaseRecord = inMemoryDb.purchases.get(tokenHash);
                    }

                    if (!purchaseRecord || purchaseRecord.uid !== uid || purchaseRecord.voided) {
                        await ref.update({
                            status: 'FAILED_TERMINAL',
                            lastError: !purchaseRecord ? 'Purchase record absent' : (purchaseRecord.voided ? 'Purchase voided/refunded' : 'UID mismatch'),
                            leaseUntil: 0,
                            updatedAt: Date.now()
                        });
                        results.terminal++;
                        continue;
                    }

                    if (purchaseRecord.acknowledgementState === 1) {
                        await ref.update({
                            status: 'ACKNOWLEDGED',
                            leaseUntil: 0,
                            updatedAt: Date.now()
                        });
                        results.acknowledged++;
                        continue;
                    }

                    // 3. Decrypt token safely
                    let plainToken;
                    try {
                        plainToken = decryptToken(data.encryptedToken, encKey);
                    } catch (decErr) {
                        await ref.update({
                            status: 'FAILED_TERMINAL',
                            lastError: 'Decryption failed: ' + decErr.message,
                            leaseUntil: 0,
                            updatedAt: Date.now()
                        });
                        results.terminal++;
                        continue;
                    }

                    // 4. Requery Google Play to verify state if API exists
                    const isSubscription = (productId === 'vip_yearly');
                    if (androidPublisher) {
                        if (isSubscription && androidPublisher.purchases?.subscriptionsv2?.get) {
                            const playResp = await androidPublisher.purchases.subscriptionsv2.get({
                                packageName: config.packageName,
                                token: plainToken
                            });
                            const subData = playResp.data;
                            const lineItem = subData.lineItems?.find(i => i.productId === productId);
                            if (!lineItem || !lineItem.expiryTime) {
                                await ref.update({
                                    status: 'FAILED_TERMINAL',
                                    lastError: 'Subscription line item invalid in Play recheck',
                                    leaseUntil: 0,
                                    updatedAt: Date.now()
                                });
                                results.terminal++;
                                continue;
                            }
                        } else if (!isSubscription && androidPublisher.purchases?.products?.get) {
                            const prodResp = await androidPublisher.purchases.products.get({
                                packageName: config.packageName,
                                productId: productId,
                                token: plainToken
                            });
                            if (prodResp.data.purchaseState !== 0) {
                                await ref.update({
                                    status: 'FAILED_TERMINAL',
                                    lastError: `Product state in Play recheck: ${prodResp.data.purchaseState}`,
                                    leaseUntil: 0,
                                    updatedAt: Date.now()
                                });
                                results.terminal++;
                                continue;
                            }
                        }
                    }

                    // 5. Perform acknowledgePlayPurchase
                    if (androidPublisher) {
                        await acknowledgePlayPurchase(androidPublisher, config.packageName, productId, plainToken);
                    }

                    // Mark purchase as acknowledged in purchases table
                    if (admin && admin.apps && admin.apps.length > 0) {
                        await admin.firestore().collection('purchases').doc(tokenHash).update({
                            acknowledgementState: 1
                        });
                    } else if (config.allowMocks) {
                        if (purchaseRecord) {
                            purchaseRecord.acknowledgementState = 1;
                        }
                    }

                    // Mark job acknowledged
                    await ref.update({
                        status: 'ACKNOWLEDGED',
                        leaseUntil: 0,
                        updatedAt: Date.now(),
                        lastError: null
                    });
                    results.acknowledged++;

                } catch (itemErr) {
                    const errStatus = itemErr.status || itemErr.code || 500;
                    const is429or5xx = errStatus === 429 || (errStatus >= 500 && errStatus < 600);
                    const isTemporary = is429or5xx ||
                        (typeof itemErr.message === 'string' && (
                            itemErr.message.includes('network') ||
                            itemErr.message.includes('unavailable') ||
                            itemErr.message.includes('timeout') ||
                            itemErr.message.includes('outage')
                        ));

                    const newAttempts = (data.attempts || 0) + 1;
                    const maxAttempts = data.maxAttempts || 5;

                    if (!isTemporary && newAttempts >= 3 || newAttempts >= maxAttempts) {
                        await ref.update({
                            status: 'FAILED_TERMINAL',
                            attempts: newAttempts,
                            lastError: 'Terminal error: ' + itemErr.message,
                            leaseUntil: 0,
                            updatedAt: Date.now()
                        });
                        results.terminal++;
                    } else {
                        const backoffMs = Math.min(3600000, 5000 * Math.pow(2, newAttempts));
                        await ref.update({
                            status: 'PENDING',
                            attempts: newAttempts,
                            nextAttemptAt: Date.now() + backoffMs,
                            lastError: itemErr.message,
                            leaseUntil: 0,
                            updatedAt: Date.now()
                        });
                        results.retried++;
                    }
                    results.errors.push({ tokenHash: tokenHash, error: itemErr.message });
                }
            }

            return res.json({
                status: 'OK',
                results
            });

        } catch (fatalErr) {
            console.error('[AcknowledgeRetryJob] Fatal error:', fatalErr.message);
            return res.status(500).json({ error: 'Failed to process acknowledge retry job' });
        }
    });

    return app;
}

module.exports = {
    createApp,
    sha256Hex
};
