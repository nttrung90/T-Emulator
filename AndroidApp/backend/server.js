/**
 * T-Emulator Backend API Service
 * Production Server Entrypoint
 */

const admin = require('firebase-admin');
const { google } = require('googleapis');
require('dotenv').config();

const { createApp } = require('./app');

const isProduction = process.env.NODE_ENV === 'production';
let firebaseAdmin = null;
let androidPublisher = null;

// Initialize Firebase Admin SDK
if (process.env.FIREBASE_SERVICE_ACCOUNT_KEY) {
    try {
        const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_KEY);
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
        firebaseAdmin = admin;
        console.log('[Firebase] Admin SDK initialized successfully.');
    } catch (e) {
        console.error('[Firebase] Fatal: Failed to parse FIREBASE_SERVICE_ACCOUNT_KEY:', e.message);
        if (isProduction) {
            process.exit(1);
        }
    }
} else if (isProduction) {
    console.error('[Firebase] Fatal: FIREBASE_SERVICE_ACCOUNT_KEY is required in production environment.');
    process.exit(1);
} else {
    console.warn('[Firebase] Warning: Running in non-production mode without Firebase service account.');
}

// Initialize Google Play Developer API (AndroidPublisher)
if (process.env.PLAY_SERVICE_ACCOUNT_KEY) {
    try {
        const playCredentials = JSON.parse(process.env.PLAY_SERVICE_ACCOUNT_KEY);
        const auth = new google.auth.GoogleAuth({
            credentials: playCredentials,
            scopes: ['https://www.googleapis.com/auth/androidpublisher']
        });
        androidPublisher = google.androidpublisher({ version: 'v3', auth });
        console.log('[PlayPublisher] AndroidPublisher client initialized successfully.');
    } catch (e) {
        console.error('[PlayPublisher] Fatal: Failed to parse PLAY_SERVICE_ACCOUNT_KEY:', e.message);
        if (isProduction) {
            process.exit(1);
        }
    }
} else if (isProduction) {
    console.error('[PlayPublisher] Fatal: PLAY_SERVICE_ACCOUNT_KEY is required in production environment.');
    process.exit(1);
} else {
    console.warn('[PlayPublisher] Warning: Running in non-production mode without Play service account.');
}

// Google Cloud Pub/Sub OIDC Token Verifier (G02, H01)
let oidcVerifier = null;
if (isProduction || process.env.PUBSUB_AUDIENCE) {
    const { OAuth2Client } = require('google-auth-library');
    const authClient = new OAuth2Client();
    oidcVerifier = async (token, expectedAudience) => {
        const audience = expectedAudience || process.env.PUBSUB_AUDIENCE;
        if (!audience) {
            throw new Error('PUBSUB_AUDIENCE is required for Pub/Sub OIDC verification');
        }
        const ticket = await authClient.verifyIdToken({
            idToken: token,
            audience: audience
        });
        const payload = ticket.getPayload();
        if (!payload) {
            throw new Error('Missing OIDC token payload');
        }
        if (payload.iss !== 'https://accounts.google.com' && payload.iss !== 'accounts.google.com') {
            throw new Error('Invalid Pub/Sub OIDC issuer: ' + payload.iss);
        }
        if (!payload.email_verified) {
            throw new Error('Pub/Sub OIDC service account email is not verified');
        }
        const expectedEmail = process.env.PUBSUB_SERVICE_ACCOUNT_EMAIL;
        if (!expectedEmail) {
            throw new Error('PUBSUB_SERVICE_ACCOUNT_EMAIL is required for trusted push service account identity');
        }
        if (payload.email !== expectedEmail) {
            throw new Error(`Unauthorized Pub/Sub service account email: expected ${expectedEmail}, got ${payload.email}`);
        }
        return payload;
    };
}

const app = createApp({
    admin: firebaseAdmin,
    androidPublisher: androidPublisher,
    verifyOidcToken: oidcVerifier,
    verifyJobOidcToken: oidcVerifier,
    config: {
        isProduction: isProduction,
        allowMocks: !isProduction && (process.env.ALLOW_IN_MEMORY_MOCKS === 'true'),
        ackTokenEncryptionKey: process.env.ACK_TOKEN_ENCRYPTION_KEY || null,
        jobSchedulerAudience: process.env.JOB_SCHEDULER_AUDIENCE || null,
        jobSchedulerSecret: process.env.JOB_SCHEDULER_SECRET || null
    }
});

const PORT = process.env.PORT || 8080;
if (process.env.NODE_ENV !== 'test') {
    app.listen(PORT, () => {
        console.log(`T-Emulator Backend running on port ${PORT} [production=${isProduction}]`);
    });
}

module.exports = app;
module.exports.createApp = createApp;
