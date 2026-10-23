const { test } = require('node:test');
const assert = require('node:assert/strict');
const { setup, call, sha256Hex } = require('./backend-fixture.cjs');

test('C22: absent Play acknowledgement API must remain unacknowledged rather than record false success', async () => {
  const f = setup();
  delete f.publisher.purchases.products.acknowledge;
  const token = 'audit-token-with-missing-ack-api';
  const result = await call(f.app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  assert.equal(result.status, 200);
  assert.equal(result.data.entitled, true, 'A temporary acknowledgement failure must preserve verified entitlement');
  assert.equal(f.records.get(`purchases/${sha256Hex(token)}`).acknowledgementState, 0, 'No Play acknowledgement call succeeded, so marking ack=1 loses the required retry');
});
