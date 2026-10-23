// Audit probes: real Express handlers, injected fake Firebase/Play, no network.
// Run: node AndroidApp/docs/audit-vip-2026-10-09/backend-regressions.cjs
// Assertions describe required behavior; failures reproduce remaining bugs.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createApp, sha256Hex } = require('../../backend/app');

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

function setup({ ackFails = false } = {}) {
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
  const publisher = { purchases: { products: {
    get: async args => { calls.get.push(args); return { data: { purchaseState: 0, acknowledgementState: 0, obfuscatedExternalAccountId: sha256Hex('user_A'), orderId: 'GPA.FAKE' } }; },
    acknowledge: async args => { calls.ack.push(args); if (ackFails) throw new Error('injected Play outage'); }
  } } };
  const app = createApp({ admin, androidPublisher: publisher, config: { isProduction: true, allowMocks: false, vipProductId: 'vip_yearly' } });
  return { app, calls, records };
}

test('R01: RTDN must reject an arbitrary bearer in production', async () => {
  const { app, records } = setup();
  const token = 'purchase_token_for_rtdn';
  await call(app, 'post', '/v1/purchases/verify', { purchaseToken: token });
  const data = Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64');
  const response = await call(app, 'post', '/v1/rtdn', { message: { data, messageId: 'forged-event' } }, 'not-a-google-jwt');
  console.log('RTDN forged bearer:', response, 'VIP:', records.get('users/user_A/entitlements/vip').isVipActive);
  assert.ok([401, 403, 503].includes(response.status), 'Forged bearer was accepted');
});

test('R02: lifetime verification must query the lifetime product', async () => {
  const { app, calls } = setup();
  await call(app, 'post', '/v1/purchases/verify', { purchaseToken: 'lifetime_purchase_token', productId: 'vip_lifetime' });
  console.log('Play get product:', calls.get[0].productId, 'ack product:', calls.ack[0].productId);
  assert.equal(calls.get[0].productId, 'vip_lifetime');
});

test('R03: retry verify must retry an unacknowledged purchase', async () => {
  const { app, calls } = setup({ ackFails: true });
  const body = { purchaseToken: 'purchase_needing_ack_retry' };
  await call(app, 'post', '/v1/purchases/verify', body);
  const retry = await call(app, 'post', '/v1/purchases/verify', body);
  console.log('ack attempts after two verifies:', calls.ack.length, 'retry:', retry);
  assert.equal(calls.ack.length, 2);
});

test('R04: failed RTDN must remain retryable with the same messageId', async () => {
  const { app, calls, records } = setup();
  const token = 'purchase_for_retry_notification';
  await call(app, 'post', '/v1/purchases/verify', { purchaseToken: token });
  const data = Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64');
  const body = { message: { data, messageId: 'retry-after-db-error' } };
  calls.failRead = true;
  const first = await call(app, 'post', '/v1/rtdn', body);
  const second = await call(app, 'post', '/v1/rtdn', body);
  console.log('RTDN retry:', first.status, second, 'VIP:', records.get('users/user_A/entitlements/vip').isVipActive);
  assert.equal(first.status, 500);
  assert.equal(records.get('users/user_A/entitlements/vip').isVipActive, false);
});

test('R05: Firebase deletion failure must not report account deleted', async () => {
  const { app, calls } = setup();
  calls.deleteFails = true;
  const response = await call(app, 'delete', '/v1/account');
  console.log('Delete after Firebase failure:', response);
  assert.ok(response.status >= 500, 'Delete incorrectly reported success');
});
