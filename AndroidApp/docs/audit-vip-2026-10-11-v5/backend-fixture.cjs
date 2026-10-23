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
    batch: () => { const pending = []; return { delete: d => pending.push(() => d.delete()), update: (d,v) => pending.push(() => d.update(v)), commit: async () => { for (const op of pending) await op(); } }; }
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
