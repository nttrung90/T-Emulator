const { test } = require('node:test');
const assert = require('node:assert/strict');
const { setup, call, sha256Hex } = require('./backend-fixture.cjs');
const { createApp } = require('../../backend/app');
const entPath = 'users/user_A/entitlements/vip';
function seed(f, token, expiry = Date.now() + 86400000) {
  f.records.set('purchases/' + sha256Hex(token), { uid: 'user_A', tokenHash: sha256Hex(token), productId: 'vip_yearly', voided: false, expiryTimeMillis: expiry, acknowledgementState: 1 });
  f.records.set(entPath, { isVipActive: true, productId: 'vip_yearly', expiryTimeMillis: expiry });
}
function rtdnApp(f) { return createApp({ admin: f.admin, androidPublisher: f.publisher, verifyOidcToken: async () => {}, config: { isProduction: true, allowMocks: false } }); }
function notification(type, token, id) { return { message: { messageId: id, data: Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', subscriptionNotification: { notificationType: type, purchaseToken: token } })).toString('base64') } }; }
function sub(f, { state = 'SUBSCRIPTION_STATE_ACTIVE', binding = sha256Hex('user_A'), product = 'vip_yearly' } = {}) {
  f.publisher.purchases.subscriptionsv2 = { get: async () => ({ data: { subscriptionState: state, acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED', externalAccountIdentifiers: { obfuscatedExternalAccountId: binding }, lineItems: [{ productId: product, expiryTime: new Date(Date.now() + 86400000).toISOString() }] } }) };
}
test('C01: failed RTDN Play query must remain retryable, not COMPLETED', async () => {
  const f = setup(), token = 'play-outage-renewal-v5'; seed(f, token, Date.now() - 1000);
  f.publisher.purchases.subscriptionsv2 = { get: async () => { throw Error('Injected Play outage'); } };
  const app = rtdnApp(f), event = notification(2, token, 'retry-renewal-v5');
  const first = await call(app, 'post', '/v1/rtdn', event);
  sub(f);
  const second = await call(app, 'post', '/v1/rtdn', event);
  const entitlement = await call(app, 'get', '/v1/entitlements');
  console.log('C01', first, second, entitlement);
  assert.ok(first.status >= 500); assert.equal(entitlement.data.isVip, true);
});
test('C02: a renewal hint with current ON_HOLD state must not grant VIP', async () => {
  const f = setup(), token = 'stale-renewal-hold-v5'; seed(f, token, Date.now() - 1000); sub(f, { state: 'SUBSCRIPTION_STATE_ON_HOLD' });
  await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'stale-renewal-hold-v5'));
  const result = await call(f.app, 'get', '/v1/entitlements'); console.log('C02', result); assert.equal(result.data.isVip, false);
});
test('C03: RTDN subscription for an unrelated product must not grant VIP', async () => {
  const f = setup(), token = 'wrong-product-rtdn-v5'; seed(f, token, Date.now() - 1000); sub(f, { product: 'unrelated_subscription' });
  await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'wrong-product-rtdn-v5'));
  const result = await call(f.app, 'get', '/v1/entitlements'); console.log('C03', result); assert.equal(result.data.isVip, false);
});
test('C04: PAUSED notification must apply current Play state', async () => {
  const f = setup(), token = 'paused-token-v5'; seed(f, token); sub(f, { state: 'SUBSCRIPTION_STATE_PAUSED' });
  await call(rtdnApp(f), 'post', '/v1/rtdn', notification(10, token, 'paused-token-v5'));
  const result = await call(f.app, 'get', '/v1/entitlements'); console.log('C04', result); assert.equal(result.data.isVip, false);
});
test('C05: delayed EXPIRED hint must not void a currently renewed ACTIVE subscription', async () => {
  const f = setup(), token = 'delayed-expiry-token-v5'; seed(f, token); sub(f);
  await call(rtdnApp(f), 'post', '/v1/rtdn', notification(13, token, 'delayed-expiry-token-v5'));
  const result = await call(f.app, 'get', '/v1/entitlements'); console.log('C05', result); assert.equal(result.data.isVip, true);
});
test('C06: legacy yearly entitlement without expiry must not be treated as lifetime', async () => {
  const f = setup(); f.records.set(entPath, { isVipActive: true, productId: 'vip_yearly', expiryTimeMillis: null });
  const result = await call(f.app, 'get', '/v1/entitlements'); console.log('C06', result); assert.equal(result.data.isVip, false);
});
test('C07: delete account while Play verify is in flight must prevent a new purchase commit', async () => {
  const f = setup();
  f.publisher.purchases.products.get = async () => {
    const deleted = await call(f.app, 'delete', '/v1/account'); assert.equal(deleted.status, 200);
    return { data: { purchaseState: 0, acknowledgementState: 1, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.INFLIGHT' } };
  };
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'new-token-during-delete-v5', productId: 'vip_lifetime' });
  console.log('C07', result, f.records.get(entPath)); assert.notEqual(result.data.entitled, true); assert.notEqual(f.records.get(entPath)?.isVipActive, true);
});
test('C08: purchase commit followed by entitlement-write failure must recover on retry', async () => {
  const f = setup(), request = { purchaseToken: 'recover-partial-commit-v5', productId: 'vip_lifetime' };
  f.calls.failEntitlementWrite = true;
  const first = await call(f.app, 'post', '/v1/purchases/verify', request);
  const second = await call(f.app, 'post', '/v1/purchases/verify', request);
  console.log('C08', first, second); assert.equal(second.data.entitled, true); assert.equal(f.records.get(entPath)?.isVipActive, true);
});
test('C09: RTDN binding mismatch must not update another user entitlement', async () => {
  const f = setup(), token = 'wrong-account-rtdn-v5'; seed(f, token, Date.now() - 1000); sub(f, { binding: sha256Hex('user_B') });
  await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'wrong-account-rtdn-v5'));
  const result = await call(f.app, 'get', '/v1/entitlements'); console.log('C09', result); assert.equal(result.data.isVip, false);
});
test('C10: renewal must not resurrect a purchase retained after account deletion', async () => {
  const f = setup(), token = 'deleted-account-renewal-v5'; seed(f, token, Date.now() - 1000); sub(f);
  Object.assign(f.records.get('purchases/' + sha256Hex(token)), { voided: true, reason: 'ACCOUNT_DELETED' });
  f.records.delete(entPath);
  await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'deleted-account-renewal-v5'));
  assert.equal(f.records.get('purchases/' + sha256Hex(token)).voided, true);
  assert.notEqual(f.records.get(entPath)?.isVipActive, true);
});
test('C11: deletion must block a new verify before destructive cleanup begins', async () => {
  const f = setup(); let releasePlay; let verification;
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
  console.log('C11', verification, f.records.get('purchases/' + sha256Hex('cleanup-window-token-v5')));
  assert.notEqual(verification.data.entitled, true);
  assert.notEqual(f.records.get(entPath)?.isVipActive, true);
});
test('C12: failed processed-event persistence must return retryable failure', async () => {
  const f = setup(), token = 'event-persistence-failure-v5'; seed(f, token); sub(f);
  f.calls.failProcessedEventWrite = true;
  const result = await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'event-persistence-failure-v5'));
  assert.ok(result.status >= 500, `Returned ${result.status} despite failed durable event persistence`);
});
test('C13: deletion tombstone must deny even a previously cached active entitlement', async () => {
  const f = setup(); seed(f, 'previous-active-before-delete-v5');
  f.records.set('users_deleted/user_A', { deletedAt: Date.now() });
  const result = await call(f.app, 'get', '/v1/entitlements');
  assert.notEqual(result.data.isVip, true, 'Fast entitlement path bypassed deletion tombstone');
});
test('C14: missing Play subscription verifier must not consume renewal notification', async () => {
  const f = setup(), token = 'missing-play-subscription-verifier-v5'; seed(f, token, Date.now() - 1000);
  const result = await call(rtdnApp(f), 'post', '/v1/rtdn', notification(2, token, 'missing-play-subscription-verifier-v5'));
  assert.ok(result.status >= 500, `Consumed renewal with HTTP ${result.status} without Play authority`);
  assert.notEqual(f.records.get('processedEvents/missing-play-subscription-verifier-v5')?.status, 'COMPLETED');
});
test('C15: production OIDC must fail closed without expected push service account identity', async () => {
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
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../backend/server.js'), 'utf8'), { require: fakeRequire, process: { env, exit: () => { throw Error('Fail-closed startup'); } }, console: { log() {}, warn() {}, error() {} }, module: { exports: {} } });
    await captured.verifyOidcToken('signed-from-untrusted-service-account', env.PUBSUB_AUDIENCE);
  } catch (error) {
    if (!/Fail-closed startup|PUBSUB_SERVICE_ACCOUNT_EMAIL|trusted.*(identity|account)|expected.*service account/i.test(error.message)) throw error;
    rejected = true;
  }
  assert.equal(rejected, true, 'Accepted OIDC without any configured trusted push identity');
});
test('C16: duplicate verify must deny a tombstoned account before cleanup finishes', async () => {
  const f = setup(), token = 'deleted-duplicate-verify-v5';
  await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  f.records.set('users_deleted/user_A', { deletedAt: Date.now() });
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  assert.notEqual(result.data.entitled, true, 'Duplicate branch bypassed deletion marker');
});
test('C17: deletion begun during recompute must prevent recreation of an entitlement document', async () => {
  const f = setup(); seed(f, 'recompute-race-deleted-v5'); f.records.delete(entPath);
  f.calls.onPurchaseQuery = async () => { f.records.set('users_deleted/user_A', { deletedAt: Date.now() }); f.records.delete(entPath); };
  const result = await call(f.app, 'get', '/v1/entitlements');
  assert.notEqual(result.data.isVip, true, 'Granted VIP after deletion began during ledger aggregation');
  assert.equal(f.records.has(entPath), false, 'Recreated entitlement document after deletion began');
});
test('C18: deletion at entitlement commit boundary must conflict atomically rather than recreate VIP', async () => {
  const f = setup(); seed(f, 'entitlement-commit-boundary-v5'); f.records.delete(entPath);
  f.calls.beforeEntitlementCommit = async () => { f.records.set('users_deleted/user_A', { deletedAt: Date.now() }); f.records.delete(entPath); };
  const result = await call(f.app, 'get', '/v1/entitlements');
  assert.notEqual(result.data.isVip, true, 'A final nontransactional recheck still raced with deletion');
  assert.equal(f.records.has(entPath), false, 'Entitlement write must be atomic with tombstone read');
});
test('C19: yearly entitlement response must expose subscription metadata for bounded client cache', async () => {
  const f = setup(); seed(f, 'yearly-metadata-contract-v5');
  const result = await call(f.app, 'get', '/v1/entitlements');
  assert.equal(result.data.productId, 'vip_yearly');
  assert.equal(result.data.isLifetime, false);
  assert.ok(result.data.expiryTimeMillis > Date.now());
});
test('C20: recomputed lifetime response must expose explicit lifetime metadata', async () => {
  const f = setup();
  await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: 'lifetime-metadata-contract-v5', productId: 'vip_lifetime' });
  f.records.delete(entPath);
  const result = await call(f.app, 'get', '/v1/entitlements');
  assert.equal(result.data.productId, 'vip_lifetime');
  assert.equal(result.data.isLifetime, true);
  assert.equal(result.data.expiryTimeMillis, null);
});
test('C21: initial and duplicate yearly verification must supply authoritative cache metadata', async () => {
  const f = setup(); sub(f);
  const request = { purchaseToken: 'verify-yearly-metadata-contract-v5', productId: 'vip_yearly' };
  for (const kind of ['initial', 'duplicate']) {
    const result = await call(f.app, 'post', '/v1/purchases/verify', request);
    assert.equal(result.data.entitled, true);
    assert.equal(result.data.productId, 'vip_yearly', `${kind} verify lacks product metadata`);
    assert.equal(result.data.isLifetime, false, `${kind} verify must not be lifetime`);
    assert.ok(result.data.expiryTimeMillis > Date.now(), `${kind} verify lacks subscription expiry needed by Android cache`);
  }
});
