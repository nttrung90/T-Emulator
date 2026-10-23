// Assertions describe required behavior. Fake external services, real app/server source.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setup, call, sha256Hex } = require('./backend-fixture.cjs');
const { createApp } = require('../../backend/app');
const entPath = 'users/user_A/entitlements/vip';
const future = () => Date.now() + 86400000;
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
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../backend/server.js'), 'utf8'), { require: fakeRequire, process: { env, exit: code => { throw Error('exit:' + code); } }, console: { log() {}, warn() {}, error() {} }, module: { exports: {} } });
  return { verifier: captured.verifyOidcToken, args: () => verifyArgs };
}

test('B01: changed messageId must still reject Firebase user token when OIDC verifier is absent', async () => {
  const f = setup();
  const token = 'rtdn_purchase_variant';
  await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  const message = { message: { messageId: 'different-user-message-83', data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64') } };
  const result = await call(f.app, 'post', '/v1/rtdn', message);
  console.log('B01', result, 'VIP=', f.records.get(entPath).isVipActive);
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
  const f = setup(); playSub(f, { product: 'different_subscription' });
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'wrong_product_token_83', productId: 'vip_yearly' });
  console.log('B04', result);
  assert.notEqual(result.data.entitled, true);
});
test('B05: yearly subscription without expiry must not become permanent VIP', async () => {
  const f = setup(); playSub(f, { expiry: null });
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'missing_expiry_token_83', productId: 'vip_yearly' });
  console.log('B05', result, f.records.get(entPath));
  assert.notEqual(result.data.entitled, true);
});
test('B06: verify must requery a renewed token even if stored expiry has passed', async () => {
  const f = setup(), token = 'renewed_purchase_token_83';
  seedYearly(f, token, Date.now() - 1000); const queries = playSub(f);
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_yearly' });
  console.log('B06', result, 'Play queries=', queries());
  assert.equal(result.data.entitled, true);
  assert.ok(queries() > 0);
});
test('B07: RTDN renewal must update yearly entitlement from Play', async () => {
  const f = setup(), token = 'rtdn_renewal_token_83';
  seedYearly(f, token, Date.now() - 1000); const queries = playSub(f);
  const app = createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
  await call(app, 'post', '/v1/rtdn', event(2, token, 'renewal-83'));
  const result = await call(app, 'get', '/v1/entitlements');
  console.log('B07', result, 'Play queries=', queries());
  assert.equal(result.data.isVip, true);
});
test('B08: RTDN on-hold must remove yearly access after Play recheck', async () => {
  const f = setup(), token = 'rtdn_hold_token_83'; seedYearly(f, token, future());
  playSub(f, { state: 'SUBSCRIPTION_STATE_ON_HOLD' });
  const app = createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
  await call(app, 'post', '/v1/rtdn', event(5, token, 'hold-83'));
  const result = await call(app, 'get', '/v1/entitlements');
  console.log('B08', result); assert.equal(result.data.isVip, false);
});
test('B09: expired other purchase must not preserve a refunded lifetime entitlement', async () => {
  const f = setup(), token = 'refunded_lifetime_83';
  await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  f.records.set('purchases/expired-other', { uid: 'user_A', productId: 'vip_yearly', voided: false, expiryTimeMillis: Date.now() - 1000 });
  const app = createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } });
  await call(app, 'post', '/v1/rtdn', { message: { messageId: 'refund-83', data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64') } });
  const result = await call(app, 'get', '/v1/entitlements');
  console.log('B09', result); assert.equal(result.data.isVip, false);
});
test('B10: an active lifetime purchase must preserve VIP when a yearly purchase expires', async () => {
  const f = setup();
  await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'active_lifetime_83', productId: 'vip_lifetime' });
  const expiry = future(); playSub(f, { expiry });
  await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'later_yearly_83', productId: 'vip_yearly' });
  // Advance the clock without changing either stored purchase.
  const realNow = Date.now;
  try {
    Date.now = () => expiry + 1000;
    const result = await call(f.app, 'get', '/v1/entitlements');
    console.log('B10', result); assert.equal(result.data.isVip, true);
  } finally { Date.now = realNow; }
});
test('B11: a still-unexpired ID token must be rejected after account deletion', async () => {
  const f = setup(); let deleted = false;
  // Firebase checks account deletion/revocation only when checkRevoked is requested.
  f.admin.auth = () => ({
    verifyIdToken: async (_token, checkRevoked) => { if (checkRevoked && deleted) throw Error('auth/user-not-found'); return { uid: 'user_A' }; },
    deleteUser: async () => { deleted = true; }
  });
  const deletion = await call(f.app, 'delete', '/v1/account'); assert.equal(deletion.status, 200);
  const result = await call(f.app, 'get', '/v1/entitlements');
  console.log('B11', result); assert.ok([401, 403].includes(result.status));
});
test('B12: a token voided between validation and commit must not be reactivated', async () => {
  const f = setup(), token = 'concurrent_void_token_83'; let transactions = 0;
  const original = f.firestore.runTransaction;
  f.firestore.runTransaction = async callback => {
    transactions++;
    if (transactions === 2) f.records.set('purchases/' + sha256Hex(token), { uid: 'user_A', productId: 'vip_lifetime', voided: true });
    return original(callback);
  };
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  console.log('B12', result, f.records.get('purchases/' + sha256Hex(token)));
  assert.notEqual(result.data.entitled, true);
  assert.equal(f.records.get('purchases/' + sha256Hex(token)).voided, true);
});
