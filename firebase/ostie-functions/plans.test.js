const test = require('node:test');
const assert = require('node:assert');
const { priceFor, planForPrice, bestPlan, mergeClaims } = require('./plans');

const prices = { proMonthly: 'price_pm', proYearly: 'price_py', business: 'price_emp' };
const sub = (price, status = 'active', app = 'ostie') => ({ status, metadata: { app }, items: { data: [{ price: { id: price } }] } });

test('opções do app viram preços da Stripe', () => {
  assert.strictEqual(priceFor('PRO_ANUAL', prices), 'price_py');
  assert.throws(() => priceFor('OUTRO', prices));
});

test('preço vira plano', () => {
  assert.strictEqual(planForPrice('price_emp', prices), 'EMPRESA');
  assert.strictEqual(planForPrice('price_pm', prices), 'PRO');
  assert.strictEqual(planForPrice('price_osone_max', prices), 'GRATIS');
});

test('vale o melhor plano ativo do OSTIE; assinaturas do OSONE e canceladas não contam', () => {
  assert.strictEqual(bestPlan([sub('price_pm'), sub('price_emp', 'canceled')], prices), 'PRO');
  assert.strictEqual(bestPlan([sub('price_emp', 'active', 'osone')], prices), 'GRATIS');
  assert.strictEqual(bestPlan([sub('price_pm', 'trialing'), sub('price_emp')], prices), 'EMPRESA');
  assert.strictEqual(bestPlan([], prices), 'GRATIS');
});

test('declarações do OSONE são mantidas', () => {
  assert.deepStrictEqual(mergeClaims({ stripeRole: 'max' }, 'PRO'), { stripeRole: 'max', ostiePlan: 'PRO' });
  assert.deepStrictEqual(mergeClaims(null, 'GRATIS'), { ostiePlan: 'GRATIS' });
});
