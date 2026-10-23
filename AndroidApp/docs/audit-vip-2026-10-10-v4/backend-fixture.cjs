// Audit probes: real Express handlers, injected fake Firebase/Play, no network.
// V4 fixture: real handlers, fake services; no network.
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
  return { app, calls, records, publisher, admin, firestore };
}


module.exports = { setup, call, sha256Hex };
