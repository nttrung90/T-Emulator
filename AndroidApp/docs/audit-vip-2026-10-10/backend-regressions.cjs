// Correct-behavior assertions against real handlers; fake services; no network.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { setup, call, sha256Hex } = require('./backend-fixture.cjs');

test('N01: app user Firebase token must not authenticate Pub/Sub', async () => {
  const { app, records } = setup();
  const token = 'existing_purchase_for_rtdn';
  await call(app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_lifetime' });
  const data = Buffer.from(JSON.stringify({ packageName: 'com.temulator.app', voidedPurchaseNotification: { purchaseToken: token } })).toString('base64');
  const response = await call(app, 'post', '/v1/rtdn', { message: { data, messageId: 'from-app-user' } });
  console.log('Firebase-user RTDN:', response.status, 'VIP:', records.get('users/user_A/entitlements/vip').isVipActive);
  assert.ok([401, 403, 503].includes(response.status));
});

test('N02: documented SUBSCRIPTION_STATE_ACTIVE string must grant yearly VIP', async () => {
  const { app, publisher } = setup();
  publisher.purchases.subscriptionsv2 = { get: async () => ({ data: {
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    externalAccountIdentifiers: { obfuscatedExternalAccountId: sha256Hex('user_A') },
    lineItems: [{ productId: 'vip_yearly', expiryTime: new Date(Date.now() + 86400000).toISOString() }]
  } }) };
  const response = await call(app, 'post', '/v1/purchases/verify', { purchaseToken: 'active_subscription_token', productId: 'vip_yearly' });
  console.log('Actual Play JSON state:', response);
  assert.equal(response.status, 200);
  assert.equal(response.data.entitled, true);
});

test('N03: expired stored subscription must not remain active on GET', async () => {
  const { app, records } = setup();
  records.set('users/user_A/entitlements/vip', { isVipActive: true, productId: 'vip_yearly', expiryTimeMillis: Date.now() - 1000 });
  const response = await call(app, 'get', '/v1/entitlements');
  console.log('Expired entitlement:', response);
  assert.equal(response.data.isVip, false);
});

test('N04: a purchase record must not resurrect a deleted entitlement', async () => {
  const { app, records } = setup();
  const token = 'purchase_record_without_entitlement';
  records.set('purchases/' + sha256Hex(token), { uid: 'user_A', productId: 'vip_yearly', acknowledgementState: 1, voided: false, expiryTimeMillis: Date.now() - 1000 });
  const response = await call(app, 'post', '/v1/purchases/verify', { purchaseToken: token, productId: 'vip_yearly' });
  console.log('Missing entitlement / expired purchase:', response);
  assert.notEqual(response.data.entitled, true);
});
