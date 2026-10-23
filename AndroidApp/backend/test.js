/**
 * Regression & Integration Tests for T-Emulator Backend
 * Covers F02, F04, F06, F08, F15
 */

const { test, describe, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createApp, sha256Hex } = require('./app');

function startTestServer(app) {
    return new Promise((resolve) => {
        const server = http.createServer(app);
        server.listen(0, '127.0.0.1', () => {
            const port = server.address().port;
            const baseUrl = `http://127.0.0.1:${port}`;
            resolve({
                baseUrl,
                close: () => new Promise((res) => server.close(res))
            });
        });
    });
}

describe('F02 — Backend Configuration & Input Validation', () => {
    test('Production mode rejects unauthenticated request with 401', async () => {
        const app = createApp({
            config: { isProduction: true, allowMocks: false }
        });
        const server = await startTestServer(app);

        try {
            const res = await fetch(`${server.baseUrl}/v1/entitlements`);
            assert.equal(res.status, 401);
            const data = await res.json();
            assert.ok(data.error.includes('Missing or malformed Authorization'));
        } finally {
            await server.close();
        }
    });

    test('Production mode rejects mock bearer fallback with 503 if Firebase not initialized', async () => {
        const app = createApp({
            config: { isProduction: true, allowMocks: false }
        });
        const server = await startTestServer(app);

        try {
            const res = await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: 'Bearer fake_test_uid' }
            });
            assert.equal(res.status, 503);
            const data = await res.json();
            assert.ok(data.error.includes('Authentication service unavailable'));
        } finally {
            await server.close();
        }
    });

    test('Verification rejects missing, non-string, or empty purchaseToken with 400', async () => {
        const app = createApp({
            config: { isProduction: false, allowMocks: true }
        });
        const server = await startTestServer(app);

        try {
            // Missing purchaseToken
            let res = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer test_user_1',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({})
            });
            assert.equal(res.status, 400);

            // Number purchaseToken
            res = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer test_user_1',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: 12345678 })
            });
            assert.equal(res.status, 400);

            // Short token (< 10 chars)
            res = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer test_user_1',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: 'short' })
            });
            assert.equal(res.status, 400);
        } finally {
            await server.close();
        }
    });

    test('Verification fails with 503 in production if Play service account is missing', async () => {
        const app = createApp({
            androidPublisher: null,
            config: { isProduction: true, allowMocks: false }
        });
        const server = await startTestServer(app);

        try {
            // Mock authentication passing by overriding middleware or using admin
            const res = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer user_without_play_creds',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: 'valid_token_length_string_12345' })
            });
            // Should fail with 503 (auth or publisher unavailable), not grant VIP!
            assert.ok(res.status === 503 || res.status === 401);
        } finally {
            await server.close();
        }
    });
});

describe('F04 — Token Ownership & Cross-Account Replay Protection', () => {
    test('Token verified by User A cannot be claimed by User B (409 Conflict)', async () => {
        const token = 'play_purchase_token_user_a_1234567890';
        const app = createApp({
            config: { isProduction: false, allowMocks: true }
        });
        const server = await startTestServer(app);

        try {
            // User A verifies purchase
            const resA = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer user_A',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token })
            });
            assert.equal(resA.status, 200);
            const dataA = await resA.json();
            assert.equal(dataA.entitled, true);
            assert.equal(dataA.status, 'GRANTED');

            // User A checks entitlement -> VIP active
            const entA = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: 'Bearer user_A' }
            })).json();
            assert.equal(entA.isVip, true);

            // User B tries to claim the SAME purchase token
            const resB = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer user_B',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token })
            });
            assert.equal(resB.status, 409);
            const dataB = await resB.json();
            assert.ok(dataB.error.includes('already claimed'));

            // User B checks entitlement -> NOT VIP
            const entB = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: 'Bearer user_B' }
            })).json();
            assert.equal(entB.isVip, false);

            // User A re-verifies (idempotency) -> 200 ALREADY_VERIFIED
            const resA2 = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer user_A',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token })
            });
            assert.equal(resA2.status, 200);
            const dataA2 = await resA2.json();
            assert.equal(dataA2.entitled, true);
            assert.equal(dataA2.status, 'ALREADY_VERIFIED');
        } finally {
            await server.close();
        }
    });

    test('Purchase verification with obfuscated account ID mismatch returns 403', async () => {
        const fakePlayPublisher = {
            purchases: {
                products: {
                    get: async () => ({
                        data: {
                            orderId: 'GPA.123-456',
                            purchaseState: 0,
                            acknowledgementState: 0,
                            obfuscatedExternalAccountId: 'different_user_account_id'
                        }
                    }),
                    acknowledge: async () => ({})
                }
            }
        };

        const app = createApp({
            androidPublisher: fakePlayPublisher,
            config: { isProduction: false, allowMocks: true }
        });
        const server = await startTestServer(app);

        try {
            const res = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer user_actual_caller',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: 'sample_token_for_mismatch_check' })
            });
            assert.equal(res.status, 403);
            const data = await res.json();
            assert.ok(data.error.includes('identifier does not match'));
        } finally {
            await server.close();
        }
    });
});

describe('F06 — RTDN Authentication, Deduplication & Scoped Revocation', () => {
    test('RTDN rejects unauthorized requests when secret or auth required in production', async () => {
        const app = createApp({
            config: {
                isProduction: true,
                allowMocks: false,
                pubSubSecret: 'secret_key_123'
            }
        });
        const server = await startTestServer(app);

        try {
            const res = await fetch(`${server.baseUrl}/v1/rtdn`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: {
                        data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app' })).toString('base64')
                    }
                })
            });
            assert.equal(res.status, 401);
        } finally {
            await server.close();
        }
    });

    test('RTDN deduplicates repeated messageId', async () => {
        const app = createApp({
            config: {
                isProduction: false,
                allowMocks: true,
                pubSubSecret: 'test_secret'
            }
        });
        const server = await startTestServer(app);

        try {
            const eventPayload = {
                packageName: 'com.temulator.app',
                oneTimeProductNotification: {
                    purchaseToken: 'test_token_123456789',
                    notificationType: 1
                }
            };

            const body = {
                message: {
                    messageId: 'event_msg_001',
                    data: Buffer.from(JSON.stringify(eventPayload)).toString('base64')
                }
            };

            // First call -> 200 OK
            const res1 = await fetch(`${server.baseUrl}/v1/rtdn`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'x-pubsub-secret': 'test_secret'
                },
                body: JSON.stringify(body)
            });
            assert.equal(res1.status, 200);
            assert.equal(await res1.text(), 'OK');

            // Duplicate call with same messageId -> 200 OK (duplicate)
            const res2 = await fetch(`${server.baseUrl}/v1/rtdn`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'x-pubsub-secret': 'test_secret'
                },
                body: JSON.stringify(body)
            });
            assert.equal(res2.status, 200);
            assert.equal(await res2.text(), 'OK (duplicate)');
        } finally {
            await server.close();
        }
    });

    test('RTDN voiding an old purchase does not revoke entitlement if user has another active purchase', async () => {
        const token1 = 'token_first_old_purchase_12345';
        const token2 = 'token_second_new_purchase_67890';

        const app = createApp({
            config: {
                isProduction: false,
                allowMocks: true,
                pubSubSecret: 'secret_rtdn'
            }
        });
        const server = await startTestServer(app);

        try {
            // 1. User verifies first purchase
            await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer multi_purchase_user',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token1 })
            });

            // 2. User verifies second purchase
            await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer multi_purchase_user',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token2 })
            });

            // Check initial VIP active
            let ent = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: 'Bearer multi_purchase_user' }
            })).json();
            assert.equal(ent.isVip, true);

            // 3. RTDN voids token1 (refunded old purchase)
            const rtdnPayload = {
                packageName: 'com.temulator.app',
                voidedPurchaseNotification: {
                    purchaseToken: token1
                }
            };

            const rtdnRes = await fetch(`${server.baseUrl}/v1/rtdn`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'x-pubsub-secret': 'secret_rtdn'
                },
                body: JSON.stringify({
                    message: {
                        messageId: 'void_msg_001',
                        data: Buffer.from(JSON.stringify(rtdnPayload)).toString('base64')
                    }
                })
            });
            assert.equal(rtdnRes.status, 200);

            // User should STILL have VIP active because token2 is valid!
            ent = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: 'Bearer multi_purchase_user' }
            })).json();
            assert.equal(ent.isVip, true, 'User should keep VIP active since token2 is not voided');

            // 4. RTDN voids token2 -> NOW VIP is revoked
            const rtdnPayload2 = {
                packageName: 'com.temulator.app',
                voidedPurchaseNotification: {
                    purchaseToken: token2
                }
            };
            await fetch(`${server.baseUrl}/v1/rtdn`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'x-pubsub-secret': 'secret_rtdn'
                },
                body: JSON.stringify({
                    message: {
                        messageId: 'void_msg_002',
                        data: Buffer.from(JSON.stringify(rtdnPayload2)).toString('base64')
                    }
                })
            });

            ent = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: 'Bearer multi_purchase_user' }
            })).json();
            assert.equal(ent.isVip, false, 'User VIP should be revoked after all purchases voided');
        } finally {
            await server.close();
        }
    });
});

describe('F08 — Verification & Acknowledgment Flow', () => {
    test('Acknowledge is called on Play Developer API during verification', async () => {
        let acknowledgedToken = null;
        const fakePlayPublisher = {
            purchases: {
                products: {
                    get: async () => ({
                        data: {
                            orderId: 'GPA.TEST-ACK-123',
                            purchaseState: 0,
                            acknowledgementState: 0 // Not acknowledged yet
                        }
                    }),
                    acknowledge: async ({ token }) => {
                        acknowledgedToken = token;
                        return {};
                    }
                }
            }
        };

        const app = createApp({
            androidPublisher: fakePlayPublisher,
            config: { isProduction: false, allowMocks: true }
        });
        const server = await startTestServer(app);

        try {
            const tokenToVerify = 'token_awaiting_play_acknowledge_123';
            const res = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer ack_user',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: tokenToVerify })
            });

            assert.equal(res.status, 200);
            assert.equal(acknowledgedToken, tokenToVerify, 'Play acknowledge should be called with token');
        } finally {
            await server.close();
        }
    });
});

describe('F15 — Account & Entitlement Deletion', () => {
    test('DELETE /v1/account revokes entitlement and deletes user', async () => {
        const app = createApp({
            config: { isProduction: false, allowMocks: true }
        });
        const server = await startTestServer(app);

        try {
            const userUid = 'user_to_be_deleted';
            const token = 'token_for_deleted_user_12345';

            // Verify purchase
            await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: `Bearer ${userUid}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token })
            });

            // Entitlement is true
            let ent = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: `Bearer ${userUid}` }
            })).json();
            assert.equal(ent.isVip, true);

            // DELETE account
            const delRes = await fetch(`${server.baseUrl}/v1/account`, {
                method: 'DELETE',
                headers: { Authorization: `Bearer ${userUid}` }
            });
            assert.equal(delRes.status, 200);
            const delData = await delRes.json();
            assert.equal(delData.success, true);

            // Entitlement is now false
            ent = await (await fetch(`${server.baseUrl}/v1/entitlements`, {
                headers: { Authorization: `Bearer ${userUid}` }
            })).json();
            assert.equal(ent.isVip, false);

            // Re-claiming the deleted user's token by another user is STILL rejected (replay lock)
            const replayRes = await fetch(`${server.baseUrl}/v1/purchases/verify`, {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer attacker_user',
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ purchaseToken: token })
            });
            assert.equal(replayRes.status, 409);
        } finally {
            await server.close();
        }
    });
});

describe('Audit Probes — R01 to R05 Regressions (V01, V02, V03, V04, V12)', () => {
    function setupAudit({ ackFails = false } = {}) {
        const records = new Map();
        const calls = { get: [], ack: [], failRead: false, deleteFails: false };
        const doc = path => ({
            path,
            collection: name => collection(`${path}/${name}`),
            get: async () => {
                if (calls.failRead) { calls.failRead = false; throw new Error('injected transient DB outage'); }
                return { exists: records.has(path), data: () => records.get(path) };
            },
            set: async value => records.set(path, { ...records.get(path), ...value }),
            update: async value => records.set(path, { ...records.get(path), ...value }),
            delete: async () => records.delete(path)
        });
        const collection = (path, filters = []) => ({
            doc: id => doc(`${path}/${id}`),
            where: (key, op, value) => collection(path, [...filters, [key, value]]),
            get: async () => {
                const rows = [...records].filter(([p, v]) => p.startsWith(path + '/') && !p.slice(path.length + 1).includes('/') && filters.every(([k, x]) => v[k] === x));
                return { empty: rows.length === 0, forEach: fn => rows.forEach(([p]) => fn({ ref: doc(p) })) };
            }
        });
        const firestore = {
            collection,
            runTransaction: async fn => fn({ get: d => d.get(), set: (d, v) => d.set(v) }),
            batch: () => { const pending = []; return { delete: d => pending.push(d), commit: async () => { for (const d of pending) await d.delete(); } }; }
        };
        const admin = { apps: [{}], auth: () => ({
            verifyIdToken: async token => { assert.equal(token, 'valid-user-token'); return { uid: 'user_A' }; },
            deleteUser: async () => { if (calls.deleteFails) throw new Error('injected Firebase outage'); }
        }), firestore: () => firestore };
        const publisher = { purchases: {
            products: {
                get: async args => { calls.get.push(args); return { data: { purchaseState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.FAKE' } }; },
                acknowledge: async args => { calls.ack.push(args); if (ackFails) throw new Error('injected Play outage'); }
            },
            subscriptions: {
                acknowledge: async args => { calls.ack.push(args); if (ackFails) throw new Error('injected Play outage'); }
            }
        } };
        const app = createApp({
            admin,
            androidPublisher: publisher,
            verifyOidcToken: async (token) => {
                if (token !== 'valid-user-token' && token !== 'valid-oidc-token') {
                    throw new Error('Invalid token');
                }
            },
            config: { isProduction: true, allowMocks: false, vipProductId: 'vip_yearly' }
        });
        return { app, calls, records };
    }

    async function callAudit(app, method, path, body = {}, bearer = 'valid-user-token') {
        const layer = app._router.stack.find(x => x.route?.path === path && x.route.methods[method]);
        const req = { body, headers: { authorization: `Bearer ${bearer}` }, query: {} };
        let status = 200, data;
        const res = { status(n) { status = n; return this; }, json(x) { data = x; return this; }, send(x) { data = x; return this; } };
        for (const handler of layer.route.stack) {
            let next = false;
            await handler.handle(req, res, () => { next = true; });
            if (!next) break;
        }
        return { status, data };
    }

    test('R01: RTDN must reject an arbitrary bearer in production (V01)', async () => {
        const { app } = setupAudit();
        const token = 'purchase_token_for_rtdn';
        await callAudit(app, 'post', '/v1/purchases/verify', { purchaseToken: token });
        const data = Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64');
        const response = await callAudit(app, 'post', '/v1/rtdn', { message: { data, messageId: 'forged-event' } }, 'not-a-google-jwt');
        assert.ok([401, 403, 503].includes(response.status), 'Forged bearer was accepted');
    });

    test('R02: lifetime verification must query the lifetime product (V02)', async () => {
        const { app, calls } = setupAudit();
        await callAudit(app, 'post', '/v1/purchases/verify', { purchaseToken: 'lifetime_purchase_token', productId: 'vip_lifetime' });
        assert.equal(calls.get[0].productId, 'vip_lifetime');
    });

    test('R03: retry verify must retry an unacknowledged purchase (V03)', async () => {
        const { app, calls } = setupAudit({ ackFails: true });
        const body = { purchaseToken: 'purchase_needing_ack_retry' };
        await callAudit(app, 'post', '/v1/purchases/verify', body);
        const retry = await callAudit(app, 'post', '/v1/purchases/verify', body);
        assert.equal(calls.ack.length, 2);
    });

    test('R04: failed RTDN must remain retryable with the same messageId (V04)', async () => {
        const { app, calls, records } = setupAudit();
        const token = 'purchase_for_retry_notification';
        await callAudit(app, 'post', '/v1/purchases/verify', { purchaseToken: token });
        const data = Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64');
        const body = { message: { data, messageId: 'retry-after-db-error' } };
        calls.failRead = true;
        const first = await callAudit(app, 'post', '/v1/rtdn', body);
        const second = await callAudit(app, 'post', '/v1/rtdn', body);
        assert.equal(first.status, 500);
        assert.equal(records.get('users/user_A/entitlements/vip').isVipActive, false);
    });

    test('R05: Firebase deletion failure must not report account deleted (V12)', async () => {
        const { app, calls } = setupAudit();
        calls.deleteFails = true;
        const response = await callAudit(app, 'delete', '/v1/account');
        assert.ok(response.status >= 500, 'Delete incorrectly reported success');
    });
});

describe('Audit Probes — N01 to N04 Regressions (G02, G03, G04)', () => {
    function setupAuditN() {
        const records = new Map();
        const calls = { get: [], ack: [] };
        const doc = path => ({
            path,
            collection: name => collection(`${path}/${name}`),
            get: async () => ({ exists: records.has(path), data: () => records.get(path) }),
            set: async value => records.set(path, { ...records.get(path), ...value }),
            update: async value => records.set(path, { ...records.get(path), ...value }),
            delete: async () => records.delete(path)
        });
        const collection = (path, filters = []) => ({
            doc: id => doc(`${path}/${id}`),
            where: (key, op, value) => collection(path, [...filters, [key, value]]),
            get: async () => {
                const rows = [...records].filter(([p, v]) => p.startsWith(path + '/') && !p.slice(path.length + 1).includes('/') && filters.every(([k, x]) => v[k] === x));
                return { empty: rows.length === 0, forEach: fn => rows.forEach(([p]) => fn({ ref: doc(p) })) };
            }
        });
        const firestore = {
            collection,
            runTransaction: async fn => fn({ get: d => d.get(), set: (d, v) => d.set(v) })
        };
        const admin = { apps: [{}], auth: () => ({
            verifyIdToken: async token => { assert.equal(token, 'valid-user-token'); return { uid: 'user_A' }; }
        }), firestore: () => firestore };
        const publisher = { purchases: { products: {
            get: async args => { calls.get.push(args); return { data: { purchaseState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.FAKE' } }; },
            acknowledge: async args => { calls.ack.push(args); }
        } } };
        const app = createApp({ admin, androidPublisher: publisher, config: { isProduction: true, allowMocks: false, vipProductId: 'vip_yearly' } });
        return { app, calls, records, publisher };
    }

    async function callAuditN(app, method, path, body = {}, bearer = 'valid-user-token') {
        const layer = app._router.stack.find(x => x.route?.path === path && x.route.methods[method]);
        const req = { body, headers: { authorization: `Bearer ${bearer}` }, query: {} };
        let status = 200, data;
        const res = { status(n) { status = n; return this; }, json(x) { data = x; return this; }, send(x) { data = x; return this; } };
        for (const handler of layer.route.stack) {
            let next = false;
            await handler.handle(req, res, () => { next = true; });
            if (!next) break;
        }
        return { status, data };
    }

    test('N01: app user Firebase token must not authenticate Pub/Sub (G02)', async () => {
        const { app } = setupAuditN();
        const token = 'existing_purchase_for_rtdn';
        await callAuditN(app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        const data = Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64');
        const response = await callAuditN(app, 'post', '/v1/rtdn', { message: { data, messageId: 'from-app-user' } });
        assert.ok([401, 403, 503].includes(response.status));
    });

    test('N02: documented SUBSCRIPTION_STATE_ACTIVE string must grant yearly VIP (G03)', async () => {
        const { app, publisher } = setupAuditN();
        publisher.purchases.subscriptionsv2 = { get: async () => ({ data: {
            subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
            acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
            externalAccountIdentifiers: { obfuscatedExternalAccountId: sha256Hex('user_A') },
            lineItems: [{ productId: 'vip_yearly', expiryTime: new Date(Date.now() + 86400000).toISOString() }]
        } }) };
        const response = await callAuditN(app, 'post', '/v1/purchases/verify', { purchaseToken: 'active_subscription_token', productId: 'vip_yearly' });
        assert.equal(response.status, 200);
        assert.equal(response.data.entitled, true);
    });

    test('N03: expired stored subscription must not remain active on GET (G04)', async () => {
        const { app, records } = setupAuditN();
        records.set('users/user_A/entitlements/vip', { isVipActive: true, productId: 'vip_yearly', expiryTimeMillis: Date.now() - 1000 });
        const response = await callAuditN(app, 'get', '/v1/entitlements');
        assert.equal(response.data.isVip, false);
    });

    test('N04: a purchase record must not resurrect a deleted entitlement (G04)', async () => {
        const { app, records } = setupAuditN();
        const token = 'purchase_record_without_entitlement';
        records.set('purchases/' + sha256Hex(token), { uid: 'user_A', productId: 'vip_yearly', acknowledgementState: 1, voided: false, expiryTimeMillis: Date.now() - 1000 });
        const response = await callAuditN(app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_yearly' });
        assert.notEqual(response.data.entitled, true);
    });
});

describe('Audit Probes — B01 to B12 Regressions (H01 to H05)', () => {
    const vm = require('node:vm');
    const fs = require('node:fs');
    const path = require('node:path');
    const entPath = 'users/user_A/entitlements/vip';
    const future = () => Date.now() + 86400000;

    function setupV4() {
        const records = new Map();
        const calls = { get: [], ack: [] };
        const doc = path => ({
            path,
            collection: name => collection(`${path}/${name}`),
            get: async () => ({ exists: records.has(path), data: () => records.get(path) }),
            set: async value => records.set(path, { ...records.get(path), ...value }),
            update: async value => records.set(path, { ...records.get(path), ...value }),
            delete: async () => records.delete(path)
        });
        const collection = (path, filters = []) => ({
            doc: id => doc(`${path}/${id}`),
            where: (key, op, value) => collection(path, [...filters, [key, value]]),
            get: async () => {
                const rows = [...records].filter(([p, v]) => p.startsWith(path + '/') && !p.slice(path.length + 1).includes('/') && filters.every(([k, x]) => v[k] === x));
                return { empty: rows.length === 0, forEach: fn => rows.forEach(([p]) => fn({ ref: doc(p) })) };
            }
        });
        const firestore = {
            collection,
            runTransaction: async fn => fn({ get: d => d.get(), set: (d, v) => d.set(v) }),
            batch: () => { const pending = []; return { delete: d => pending.push(d), commit: async () => { for (const d of pending) await d.delete(); } }; }
        };
        const admin = { apps: [{}], auth: () => ({
            verifyIdToken: async (token, checkRevoked) => {
                assert.equal(token, 'valid-user-token');
                return { uid: 'user_A' };
            },
            deleteUser: async () => {}
        }), firestore: () => firestore };
        const publisher = { purchases: { products: {
            get: async args => { calls.get.push(args); return { data: { purchaseState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.FAKE' } }; },
            acknowledge: async args => { calls.ack.push(args); }
        } } };
        const app = createApp({ admin, androidPublisher: publisher, config: { isProduction: true, allowMocks: false, vipProductId: 'vip_yearly' } });
        return { app, calls, records, publisher, admin, firestore };
    }

    function playSub(fixture, { product = 'vip_yearly', expiry = future(), state = 'SUBSCRIPTION_STATE_ACTIVE' } = {}) {
        let queries = 0;
        fixture.publisher.purchases.subscriptionsv2 = { get: async () => {
            queries++;
            return { data: {
                subscriptionState: state,
                acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
                externalAccountIdentifiers: { obfuscatedExternalAccountId: sha256Hex('user_A') },
                lineItems: [{ productId: product, ...(expiry === null ? {} : { expiryTime: new Date(expiry).toISOString() }) }]
            } };
        } };
        return () => queries;
    }

    function event(type, token, id) {
        return { message: { messageId: id, data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', subscriptionNotification: { notificationType: type, purchaseToken: token } })).toString('base64') } };
    }

    function seedYearly(fixture, token, expiry) {
        const hash = sha256Hex(token);
        fixture.records.set('purchases/' + hash, { uid: 'user_A', productId: 'vip_yearly', acknowledgementState: 1, voided: false, expiryTimeMillis: expiry });
        fixture.records.set(entPath, { isVipActive: true, productId: 'vip_yearly', activeTokenHash: hash, expiryTimeMillis: expiry });
    }

    function loadServerVerifier({ audience = 'https://service.example/v1/rtdn', email = 'untrusted@other-project.iam.gserviceaccount.com' } = {}) {
        let captured, verifyArgs;
        const env = { NODE_ENV: 'production', FIREBASE_SERVICE_ACCOUNT_KEY: '{}', PLAY_SERVICE_ACCOUNT_KEY: '{}', ...(audience === null ? {} : { PUBSUB_AUDIENCE: audience }), PUBSUB_SERVICE_ACCOUNT_EMAIL: 'trusted@project.iam.gserviceaccount.com' };
        const fakeRequire = name => {
            if (name === 'firebase-admin') return { initializeApp() {}, credential: { cert: x => x } };
            if (name === 'googleapis') return { google: { auth: { GoogleAuth: class {} }, androidpublisher: () => ({}) } };
            if (name === 'dotenv') return { config() {} };
            if (name === 'google-auth-library') return { OAuth2Client: class { async verifyIdToken(args) { verifyArgs = args; return { getPayload: () => ({ iss: 'https://accounts.google.com', email_verified: true, email }) }; } } };
            if (name === './app') return { createApp: options => { captured = options; return { listen() {} }; } };
            throw Error('Unexpected require: ' + name);
        };
        vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), { require: fakeRequire, process: { env, exit: code => { throw Error('exit:' + code); } }, console: { log() {}, warn() {}, error() {} }, module: { exports: {} } });
        return { verifier: captured.verifyOidcToken, args: () => verifyArgs };
    }

    async function call(app, method, path, body = {}, bearer = 'valid-user-token') {
        const layer = app._router.stack.find(x => x.route?.path === path && x.route.methods[method]);
        const req = { body, headers: { authorization: `Bearer ${bearer}` }, query: {} };
        let status = 200, data;
        const res = { status(n) { status = n; return this; }, json(x) { data = x; return this; }, send(x) { data = x; return this; } };
        for (const handler of layer.route.stack) {
            let next = false;
            await handler.handle(req, res, () => { next = true; });
            if (!next) break;
        }
        return { status, data };
    }

    test('B01: changed messageId must still reject Firebase user token when OIDC verifier is absent', async () => {
        const f = setupV4();
        const token = 'rtdn_purchase_variant';
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        const message = { message: { messageId: 'different-user-message-83', data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64') } };
        const result = await call(f.app, 'post', '/v1/rtdn', message);
        assert.ok([401, 403, 503].includes(result.status));
        assert.equal(f.records.get(entPath).isVipActive, true);
    });

    test('B02: production OIDC verifier must reject a different verified service account email', async () => {
        const { verifier } = loadServerVerifier();
        await assert.rejects(() => verifier('signed-oidc-from-other-service-account', 'https://service.example/v1/rtdn'));
    });

    test('B03: production must fail closed when expected Pub/Sub audience is missing', async () => {
        let rejected = false;
        try { const { verifier } = loadServerVerifier({ audience: null, email: 'trusted@project.iam.gserviceaccount.com' }); await verifier('signed-oidc', null); }
        catch { rejected = true; }
        assert.equal(rejected, true);
    });

    test('B04: a subscription for another product must not grant vip_yearly', async () => {
        const f = setupV4(); playSub(f, { product: 'different_subscription' });
        const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'wrong_product_token_83', productId: 'vip_yearly' });
        assert.notEqual(result.data.entitled, true);
    });

    test('B05: yearly subscription without expiry must not become permanent VIP', async () => {
        const f = setupV4(); playSub(f, { expiry: null });
        const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'missing_expiry_token_83', productId: 'vip_yearly' });
        assert.notEqual(result.data.entitled, true);
    });

    test('B06: verify must requery a renewed token even if stored expiry has passed', async () => {
        const f = setupV4(), token = 'renewed_purchase_token_83';
        seedYearly(f, token, Date.now() - 1000); const queries = playSub(f);
        const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_yearly' });
        assert.equal(result.data.entitled, true);
        assert.ok(queries() > 0);
    });

    test('B07: RTDN renewal must update yearly entitlement from Play', async () => {
        const f = setupV4(), token = 'rtdn_renewal_token_83';
        seedYearly(f, token, Date.now() - 1000); const queries = playSub(f);
        const app = createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
        await call(app, 'post', '/v1/rtdn', event(2, token, 'renewal-83'));
        const result = await call(app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, true);
    });

    test('B08: RTDN on-hold must remove yearly access after Play recheck', async () => {
        const f = setupV4(), token = 'rtdn_hold_token_83'; seedYearly(f, token, future());
        playSub(f, { state: 'SUBSCRIPTION_STATE_ON_HOLD' });
        const app = createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
        await call(app, 'post', '/v1/rtdn', event(5, token, 'hold-83'));
        const result = await call(app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('B09: expired other purchase must not preserve a refunded lifetime entitlement', async () => {
        const f = setupV4(), token = 'refunded_lifetime_83';
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        f.records.set('purchases/expired-other', { uid: 'user_A', productId: 'vip_yearly', voided: false, expiryTimeMillis: Date.now() - 1000 });
        const app = createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
        await call(app, 'post', '/v1/rtdn', { message: { messageId: 'refund-83', data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64') } });
        const result = await call(app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('B10: an active lifetime purchase must preserve VIP when a yearly purchase expires', async () => {
        const f = setupV4();
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'active_lifetime_83', productId: 'vip_lifetime' });
        const expiry = future(); playSub(f, { expiry });
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'later_yearly_83', productId: 'vip_yearly' });
        const realNow = Date.now;
        try {
            Date.now = () => expiry + 1000;
            const result = await call(f.app, 'get', '/v1/entitlements');
            assert.equal(result.data.isVip, true);
        } finally { Date.now = realNow; }
    });

    test('B11: a still-unexpired ID token must be rejected after account deletion', async () => {
        const f = setupV4(); let deleted = false;
        f.admin.auth = () => ({
            verifyIdToken: async (_token, checkRevoked) => { if (checkRevoked && deleted) throw Error('auth/user-not-found'); return { uid: 'user_A' }; },
            deleteUser: async () => { deleted = true; }
        });
        const deletion = await call(f.app, 'delete', '/v1/account'); assert.equal(deletion.status, 200);
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.ok([401, 403].includes(result.status));
    });

    test('B12: a token voided between validation and commit must not be reactivated', async () => {
        const f = setupV4(), token = 'concurrent_void_token_83'; let transactions = 0;
        const original = f.firestore.runTransaction;
        f.firestore.runTransaction = async callback => {
            transactions++;
            if (transactions === 2) f.records.set('purchases/' + sha256Hex(token), { uid: 'user_A', productId: 'vip_lifetime', voided: true });
            return original(callback);
        };
        const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        assert.notEqual(result.data.entitled, true);
        assert.equal(f.records.get('purchases/' + sha256Hex(token)).voided, true);
    });
});

describe('Audit Probes — C01 to C10 Regressions (V5 / J01, J02, J03)', () => {
    const entPath = 'users/user_A/entitlements/vip';

    function setupV5() {
        const records = new Map();
        const calls = { get: [], ack: [], failRead: false, deleteFails: false };
        const doc = path => ({
            path,
            collection: name => collection(`${path}/${name}`),
            get: async () => {
                if (calls.failRead) { calls.failRead = false; throw new Error('injected transient DB outage'); }
                const value = structuredClone(records.get(path));
                return { exists: records.has(path), ref: doc(path), id: path.split('/').at(-1), data: () => structuredClone(value) };
            },
            set: async value => {
                if (path.endsWith('/entitlements/vip') && calls.beforeEntitlementCommit) { const hook = calls.beforeEntitlementCommit; calls.beforeEntitlementCommit = null; await hook(); }
                if (calls.failProcessedEventWrite && path.startsWith('processedEvents/')) { calls.failProcessedEventWrite = false; throw new Error('injected processed event write failure'); }
                if (calls.failEntitlementWrite && path.endsWith('/entitlements/vip')) { calls.failEntitlementWrite = false; throw new Error('injected entitlement write failure'); }
                return records.set(path, { ...records.get(path), ...value });
            },
            update: async value => records.set(path, { ...records.get(path), ...value }),
            delete: async () => records.delete(path)
        });
        const collection = (path, filters = []) => ({
            doc: id => doc(`${path}/${id}`),
            where: (key, op, value) => collection(path, [...filters, [key, value]]),
            get: async () => {
                const rows = [...records].filter(([p, v]) => p.startsWith(path + '/') && !p.slice(path.length + 1).includes('/') && filters.every(([k, x]) => v[k] === x));
                if (path === 'purchases' && calls.onPurchaseQuery) { const hook = calls.onPurchaseQuery; calls.onPurchaseQuery = null; await hook(); }
                const docs = rows.map(([p, v]) => ({ ref: doc(p), id: p.split('/').at(-1), exists: true, data: () => structuredClone(v) }));
                return { empty: rows.length === 0, docs, size: docs.length, forEach: fn => docs.forEach(fn) };
            }
        });
        const firestore = {
            collection,
            runTransaction: async fn => {
                for (let attempt = 0; attempt < 5; attempt++) {
                    const pending = [];
                    const reads = [];
                    const transaction = {
                        get: async d => {
                            if (pending.length) throw Error('Firestore requires all transaction reads before writes');
                            const snapshot = await d.get();
                            if (d.path) reads.push([d.path, JSON.stringify(snapshot.data())]);
                            return snapshot;
                        },
                        set: (d, v) => { const write = () => d.set(v); write.docPath = d.path; pending.push(write); return transaction; },
                        update: (d, v) => { pending.push(() => d.update(v)); return transaction; },
                        delete: d => { pending.push(() => d.delete()); return transaction; }
                    };
                    const result = await fn(transaction);
                    if (pending.some(write => write.docPath?.endsWith('/entitlements/vip')) && calls.beforeEntitlementCommit) { const hook = calls.beforeEntitlementCommit; calls.beforeEntitlementCommit = null; await hook(); }
                    if (reads.some(([path, value]) => JSON.stringify(records.get(path)) !== value)) continue;
                    const before = structuredClone(records);
                    try { for (const write of pending) await write(); }
                    catch (error) { records.clear(); for (const [k, v] of before) records.set(k, v); throw error; }
                    return result;
                }
                throw Error('Injected transaction conflict could not be resolved');
            },
            batch: () => {
                const pending = [];
                return {
                    delete: d => pending.push(() => d.delete()),
                    update: (d, v) => pending.push(() => d.update(v)),
                    commit: async () => { for (const op of pending) await op(); }
                };
            }
        };
        const admin = { apps: [{}], auth: () => ({
            verifyIdToken: async token => { assert.equal(token, 'valid-user-token'); return { uid: 'user_A' }; },
            deleteUser: async () => { if (calls.deleteFails) throw new Error('injected Firebase outage'); }
        }), firestore: () => firestore };
        const publisher = { purchases: { products: {
            get: async args => { calls.get.push(args); return { data: { purchaseState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.FAKE' } }; },
            acknowledge: async args => { calls.ack.push(args); }
        } } };
        const app = createApp({ admin, androidPublisher: publisher, config: { isProduction: true, allowMocks: false, vipProductId: 'vip_yearly' } });
        return { app, calls, records, publisher, admin, firestore };
    }

    function seedV5(f, token, expiry = Date.now() + 86400000) {
        f.records.set('purchases/' + sha256Hex(token), { uid: 'user_A', tokenHash: sha256Hex(token), productId: 'vip_yearly', voided: false, expiryTimeMillis: expiry, acknowledgementState: 1 });
        f.records.set(entPath, { isVipActive: true, productId: 'vip_yearly', expiryTimeMillis: expiry });
    }

    function rtdnApp(f) {
        return createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
    }

    function notification(type, token, id) {
        return { message: { messageId: id, data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', subscriptionNotification: { notificationType: type, purchaseToken: token } })).toString('base64') } };
    }

    function subV5(f, { state = 'SUBSCRIPTION_STATE_ACTIVE', binding = sha256Hex('user_A'), product = 'vip_yearly' } = {}) {
        f.publisher.purchases.subscriptionsv2 = { get: async () => ({ data: { subscriptionState: state, acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED', externalAccountIdentifiers: { obfuscatedExternalAccountId: binding }, lineItems: [{ productId: product, expiryTime: new Date(Date.now() + 86400000).toISOString() }] } }) };
    }

    async function call(app, method, path, body = {}, bearer = 'valid-user-token') {
        const layer = app._router.stack.find(x => x.route?.path === path && x.route.methods[method]);
        const req = { body, headers: { authorization: `Bearer ${bearer}` }, query: {} };
        let status = 200, data;
        const res = { status(n) { status = n; return this; }, json(x) { data = x; return this; }, send(x) { data = x; return this; } };
        for (const handler of layer.route.stack) {
            let next = false;
            await handler.handle(req, res, () => { next = true; });
            if (!next) break;
        }
        return { status, data };
    }

    test('C01: failed RTDN Play query must remain retryable, not COMPLETED (J01)', async () => {
        const f = setupV5(), token = 'play-outage-renewal-v5'; seedV5(f, token, Date.now() - 1000);
        f.publisher.purchases.subscriptionsv2 = { get: async () => { throw Error('Injected Play outage'); } };
        const app = rtdnApp(f), event = notification(2, token, 'retry-renewal-v5');
        const first = await call(app, 'post', '/v1/rtdn', event);
        subV5(f);
        const second = await call(app, 'post', '/v1/rtdn', event);
        const entitlement = await call(app, 'get', '/v1/entitlements');
        assert.ok(first.status >= 500);
        assert.equal(entitlement.data.isVip, true);
    });

    test('C02: a renewal hint with current ON_HOLD state must not grant VIP (J01)', async () => {
        const f = setupV5(), token = 'stale-renewal-hold-v5'; seedV5(f, token, Date.now() - 1000); subV5(f, { state: 'SUBSCRIPTION_STATE_ON_HOLD' });
        await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'stale-renewal-hold-v5'));
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('C03: RTDN subscription for an unrelated product must not grant VIP (J01)', async () => {
        const f = setupV5(), token = 'wrong-product-rtdn-v5'; seedV5(f, token, Date.now() - 1000); subV5(f, { product: 'unrelated_subscription' });
        await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'wrong-product-rtdn-v5'));
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('C04: PAUSED notification must apply current Play state (J01)', async () => {
        const f = setupV5(), token = 'paused-token-v5'; seedV5(f, token); subV5(f, { state: 'SUBSCRIPTION_STATE_PAUSED' });
        await call(rtdnApp(f), 'post', '/v1/rtdn', notification(10, token, 'paused-token-v5'));
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('C05: delayed EXPIRED hint must not void a currently renewed ACTIVE subscription (J01)', async () => {
        const f = setupV5(), token = 'delayed-expiry-token-v5'; seedV5(f, token); subV5(f);
        await call(rtdnApp(f), 'post', '/v1/rtdn', notification(13, token, 'delayed-expiry-token-v5'));
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, true);
    });

    test('C06: legacy yearly entitlement without expiry must not be treated as lifetime (J02)', async () => {
        const f = setupV5(); f.records.set(entPath, { isVipActive: true, productId: 'vip_yearly', expiryTimeMillis: null });
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('C07: delete account while Play verify is in flight must prevent a new purchase commit (J03)', async () => {
        const f = setupV5();
        f.publisher.purchases.products.get = async () => {
            const deleted = await call(f.app, 'delete', '/v1/account'); assert.equal(deleted.status, 200);
            return { data: { purchaseState: 0, acknowledgementState: 1, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.INFLIGHT' } };
        };
        const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'new-token-during-delete-v5', productId: 'vip_lifetime' });
        assert.notEqual(result.data.entitled, true);
        assert.notEqual(f.records.get(entPath)?.isVipActive, true);
    });

    test('C08: purchase commit followed by entitlement-write failure must recover on retry (J02)', async () => {
        const f = setupV5(), request = { purchaseToken: 'recover-partial-commit-v5', productId: 'vip_lifetime' };
        f.calls.failEntitlementWrite = true;
        const first = await call(f.app, 'post', '/v1/purchases/verify', request);
        const second = await call(f.app, 'post', '/v1/purchases/verify', request);
        assert.equal(second.data.entitled, true);
        assert.equal(f.records.get(entPath)?.isVipActive, true);
    });

    test('C09: RTDN binding mismatch must not update another user entitlement (J01)', async () => {
        const f = setupV5(), token = 'wrong-account-rtdn-v5'; seedV5(f, token, Date.now() - 1000); subV5(f, { binding: sha256Hex('user_B') });
        await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'wrong-account-rtdn-v5'));
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.isVip, false);
    });

    test('C10: renewal must not resurrect a purchase retained after account deletion (J01)', async () => {
        const f = setupV5(), token = 'deleted-account-renewal-v5'; seedV5(f, token, Date.now() - 1000); subV5(f);
        Object.assign(f.records.get('purchases/' + sha256Hex(token)), { voided: true, reason: 'ACCOUNT_DELETED' });
        f.records.delete(entPath);
        await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'deleted-account-renewal-v5'));
        assert.equal(f.records.get('purchases/' + sha256Hex(token)).voided, true);
        assert.notEqual(f.records.get(entPath)?.isVipActive, true);
    });

    test('C11: deletion must block a new verify before destructive cleanup begins (J03)', async () => {
        const f = setupV5(); let releasePlay; let verification;
        const playGate = new Promise(resolve => { releasePlay = resolve; });
        f.publisher.purchases.products.get = async () => {
            await playGate;
            return { data: { purchaseState: 0, acknowledgementState: 1, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.DELETE_WINDOW' } };
        };
        const verifyPromise = call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'cleanup-window-token-v5', productId: 'vip_lifetime' });
        const originalBatch = f.firestore.batch;
        f.firestore.batch = () => {
            const batch = originalBatch(); const commit = batch.commit;
            batch.commit = async () => { await commit(); releasePlay(); verification = await verifyPromise; };
            return batch;
        };
        const deletion = await call(f.app, 'delete', '/v1/account');
        assert.equal(deletion.status, 200);
        assert.notEqual(verification.data.entitled, true);
        assert.notEqual(f.records.get(entPath)?.isVipActive, true);
    });

    test('C12: failed processed-event persistence must return retryable failure (J01)', async () => {
        const f = setupV5(), token = 'event-persistence-failure-v5'; seedV5(f, token); subV5(f);
        f.calls.failProcessedEventWrite = true;
        const result = await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'event-persistence-failure-v5'));
        assert.ok(result.status >= 500);
    });

    test('C13: deletion tombstone must deny even a previously cached active entitlement (J03)', async () => {
        const f = setupV5(); seedV5(f, 'previous-active-before-delete-v5');
        f.records.set('users_deleted/user_A', { deletedAt: Date.now() });
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.notEqual(result.data.isVip, true);
    });

    test('C14: missing Play subscription verifier must not consume renewal notification (J01)', async () => {
        const f = setupV5(), token = 'missing-play-subscription-verifier-v5'; seedV5(f, token, Date.now() - 1000);
        const result = await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'missing-play-subscription-verifier-v5'));
        assert.ok(result.status >= 500);
        assert.notEqual(f.records.get('processedEvents/missing-play-subscription-verifier-v5')?.status, 'COMPLETED');
    });

    test('C15: production OIDC must fail closed without expected push service account identity (H01)', async () => {
        const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path');
        let captured, rejected = false;
        const env = { NODE_ENV: 'production', FIREBASE_SERVICE_ACCOUNT_KEY: '{}', PLAY_SERVICE_ACCOUNT_KEY: '{}', PUBSUB_AUDIENCE: 'https://backend.example/v1/rtdn' };
        const fakeRequire = name => {
            if (name === 'firebase-admin') return { initializeApp() {}, credential: { cert: x => x } };
            if (name === 'googleapis') return { google: { auth: { GoogleAuth: class {} }, androidpublisher: () => ({}) } };
            if (name === 'dotenv') return { config() {} };
            if (name === 'google-auth-library') return { OAuth2Client: class { async verifyIdToken() { return { getPayload: () => ({ iss: 'https://accounts.google.com', email_verified: true, email: 'untrusted@other-project.iam.gserviceaccount.com' }) }; } } };
            if (name === './app') return { createApp: options => { captured = options; return { listen() {} }; } };
            throw Error('Unexpected module ' + name);
        };
        try {
            vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), { require: fakeRequire, process: { env, exit: () => { throw Error('Fail-closed startup'); } }, console: { log() {}, warn() {}, error() {} }, module: { exports: {} } });
            await captured.verifyOidcToken('signed-from-untrusted-service-account', env.PUBSUB_AUDIENCE);
        } catch (error) {
            if (!/Fail-closed startup|PUBSUB_SERVICE_ACCOUNT_EMAIL|trusted.*(identity|account)|expected.*service account/i.test(error.message)) throw error;
            rejected = true;
        }
        assert.equal(rejected, true);
    });

    test('C16: duplicate verify must deny a tombstoned account before cleanup finishes (J03)', async () => {
        const f = setupV5(), token = 'deleted-duplicate-verify-v5';
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        f.records.set('users_deleted/user_A', { deletedAt: Date.now() });
        const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        assert.notEqual(result.data.entitled, true);
    });

    test('C17: deletion begun during recompute must prevent recreation of an entitlement document (J03)', async () => {
        const f = setupV5(); seedV5(f, 'recompute-race-deleted-v5'); f.records.delete(entPath);
        f.calls.onPurchaseQuery = async () => { f.records.set('users_deleted/user_A', { deletedAt: Date.now() }); f.records.delete(entPath); };
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.notEqual(result.data.isVip, true);
        assert.equal(f.records.has(entPath), false);
    });

    test('C18: deletion at entitlement commit boundary must conflict atomically rather than recreate VIP (P0, C18)', async () => {
        const f = setupV5(); seedV5(f, 'entitlement-commit-boundary-v5'); f.records.delete(entPath);
        f.calls.beforeEntitlementCommit = async () => { f.records.set('users_deleted/user_A', { deletedAt: Date.now() }); f.records.delete(entPath); };
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.notEqual(result.data.isVip, true);
        assert.equal(f.records.has(entPath), false);
    });

    test('C19: yearly entitlement response must expose subscription metadata for bounded client cache (J01, C19)', async () => {
        const f = setupV5(); seedV5(f, 'yearly-metadata-contract-v5');
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.productId, 'vip_yearly');
        assert.equal(result.data.isLifetime, false);
        assert.ok(result.data.expiryTimeMillis > Date.now());
    });

    test('C20: recomputed lifetime response must expose explicit lifetime metadata (J01, C20)', async () => {
        const f = setupV5();
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'lifetime-metadata-contract-v5', productId: 'vip_lifetime' });
        f.records.delete(entPath);
        const result = await call(f.app, 'get', '/v1/entitlements');
        assert.equal(result.data.productId, 'vip_lifetime');
        assert.equal(result.data.isLifetime, true);
        assert.equal(result.data.expiryTimeMillis, null);
    });

    test('C21: initial and duplicate yearly verification must supply authoritative cache metadata (J01, C21)', async () => {
        const f = setupV5(); subV5(f);
        const request = { purchaseToken: 'verify-yearly-metadata-contract-v5', productId: 'vip_yearly' };
        for (const kind of ['initial', 'duplicate']) {
            const result = await call(f.app, 'post', '/v1/purchases/verify', request);
            assert.equal(result.data.entitled, true);
            assert.equal(result.data.productId, 'vip_yearly', `${kind} verify lacks product metadata`);
            assert.equal(result.data.isLifetime, false, `${kind} verify must not be lifetime`);
            assert.ok(result.data.expiryTimeMillis > Date.now(), `${kind} verify lacks subscription expiry needed by Android cache`);
        }
    });

    function jobApp(f, options = {}) {
        return createApp({
            admin: f.admin,
            androidPublisher: f.publisher,
            verifyJobOidcToken: options.verifyJobOidcToken || (async () => {}),
            config: {
                isProduction: true,
                allowMocks: false,
                vipProductId: 'vip_yearly',
                jobSchedulerSecret: 'trusted-job-secret-123',
                ...options.config
            }
        });
    }

    test('R5-01: unauthorized access to acknowledge-retry endpoint must be denied (user token or no auth)', async () => {
        const f = setupV5();
        const app = jobApp(f);

        // Deny user Firebase token
        const userAttempt = await call(app, 'post', '/v1/jobs/acknowledge-retry', {}, 'valid-user-token');
        assert.equal(userAttempt.status, 403, 'Firebase user token must be forbidden from running background jobs');

        // Deny missing authorization header in production
        const layer = app._router.stack.find(x => x.route?.path === '/v1/jobs/acknowledge-retry' && x.route.methods.post);
        let status = 200, data;
        const res = { status(n) { status = n; return this; }, json(x) { data = x; return this; } };
        for (const handler of layer.route.stack) {
            let next = false;
            await handler.handle({ headers: {}, body: {} }, res, () => { next = true; });
            if (!next) break;
        }
        assert.equal(status, 401, 'Unauthenticated request must be rejected with 401');
    });

    test('R5-02: Play acknowledge failure during verify persists durable pending ack task with encrypted token', async () => {
        const f = setupV5();
        delete f.publisher.purchases.products.acknowledge; // Simulate ack API outage
        const token = 'durable-ack-test-token-v5';
        const hash = sha256Hex(token);

        const verifyResult = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        assert.equal(verifyResult.status, 200);
        assert.equal(verifyResult.data.entitled, true);

        // Purchase must be recorded with ackState=0
        const purchaseDoc = f.records.get(`purchases/${hash}`);
        assert.ok(purchaseDoc);
        assert.equal(purchaseDoc.acknowledgementState, 0);

        // Pending ack doc must exist in pending_acks
        const pendingDoc = f.records.get(`pending_acks/${hash}`);
        assert.ok(pendingDoc, 'Pending ack doc must be durably persisted');
        assert.equal(pendingDoc.tokenHash, hash);
        assert.equal(pendingDoc.uid, 'user_A');
        assert.equal(pendingDoc.status, 'PENDING');
        assert.ok(pendingDoc.encryptedToken);
        assert.ok(pendingDoc.encryptedToken.iv);
        assert.ok(pendingDoc.encryptedToken.ciphertext);
        assert.ok(pendingDoc.encryptedToken.authTag);

        // Token must NEVER be stored in plaintext
        assert.notEqual(JSON.stringify(pendingDoc).includes(token), true, 'Raw purchase token must never be stored in plaintext');
    });

    test('R5-03: simulated server restart runs background job to acknowledge without client retry', async () => {
        const f = setupV5();
        delete f.publisher.purchases.products.acknowledge;
        const token = 'server-restart-ack-token-v5';
        const hash = sha256Hex(token);

        // 1. Initial verify fails acknowledge
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
        assert.equal(f.records.get(`purchases/${hash}`).acknowledgementState, 0);

        // 2. Play service recovers
        let ackCalledWith = null;
        f.publisher.purchases.products.acknowledge = async (args) => { ackCalledWith = args; };

        // 3. Server restarts (new App instance created sharing same database)
        const restartedApp = jobApp(f);

        // Make pending ack ready for processing now
        const pendingDoc = f.records.get(`pending_acks/${hash}`);
        pendingDoc.nextAttemptAt = Date.now() - 1000;

        // 4. Job runner executes background retry
        const jobResult = await call(restartedApp, 'post', '/v1/jobs/acknowledge-retry', {}, 'trusted-scheduler-token');
        assert.equal(jobResult.status, 200);
        assert.equal(jobResult.data.results.acknowledged, 1);
        assert.ok(ackCalledWith);
        assert.equal(ackCalledWith.token, token);

        // 5. Verify purchase record in DB is now acknowledged
        assert.equal(f.records.get(`purchases/${hash}`).acknowledgementState, 1);
        assert.equal(f.records.get(`pending_acks/${hash}`).status, 'ACKNOWLEDGED');
    });

    test('R5-04: concurrency lease prevents duplicate worker from double-processing', async () => {
        const f = setupV5();
        const token = 'concurrent-lease-token-v5';
        const hash = sha256Hex(token);
        delete f.publisher.purchases.products.acknowledge;
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });

        // Simulate Worker 1 holding active lease
        const pendingDoc = f.records.get(`pending_acks/${hash}`);
        pendingDoc.status = 'IN_PROGRESS';
        pendingDoc.leaseUntil = Date.now() + 60000; // 60s active lease

        f.publisher.purchases.products.acknowledge = async () => {};
        const worker2App = jobApp(f);

        // Worker 2 attempts to process batch
        const worker2Result = await call(worker2App, 'post', '/v1/jobs/acknowledge-retry', {}, 'trusted-scheduler-token');
        assert.equal(worker2Result.status, 200);
        assert.equal(worker2Result.data.results.scanned, 0, 'Worker 2 must skip actively leased items');
    });

    test('R5-05: tombstoned user account aborts background job without Play grant', async () => {
        const f = setupV5();
        const token = 'deleted-user-ack-token-v5';
        const hash = sha256Hex(token);
        delete f.publisher.purchases.products.acknowledge;
        await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });

        // User deletes account
        f.records.set('users_deleted/user_A', { deletedAt: Date.now() });

        let ackCalled = false;
        f.publisher.purchases.products.acknowledge = async () => { ackCalled = true; };
        const app = jobApp(f);

        const pendingDoc = f.records.get(`pending_acks/${hash}`);
        pendingDoc.nextAttemptAt = Date.now() - 1000;

        const jobResult = await call(app, 'post', '/v1/jobs/acknowledge-retry', {}, 'trusted-scheduler-token');
        assert.equal(jobResult.status, 200);
        assert.equal(jobResult.data.results.terminal, 1);
        assert.equal(ackCalled, false, 'Must not acknowledge or grant VIP when account is deleted');
        assert.equal(f.records.get(`pending_acks/${hash}`).status, 'FAILED_TERMINAL');
    });
});

