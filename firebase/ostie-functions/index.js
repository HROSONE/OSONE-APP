// Cloud Functions do OSTIE (codebase "ostie", separada das funções do OSONE no mesmo projeto Firebase).
// Assinatura pela Stripe (mesma conta do OSONE) e plano gravado no login Firebase como "ostiePlan".
// Segredos ficam no Secret Manager (firebase functions:secrets:set), nunca no código.

const { onRequest } = require('firebase-functions/v2/https');
const { defineSecret, defineString } = require('firebase-functions/params');
const { setGlobalOptions } = require('firebase-functions/v2');
const admin = require('firebase-admin');
const Stripe = require('stripe');
const { priceFor, bestPlan, mergeClaims } = require('./plans');

admin.initializeApp();
setGlobalOptions({ region: 'us-central1', maxInstances: 10 });

const STRIPE_SECRET_KEY = defineSecret('STRIPE_SECRET_KEY');
const STRIPE_WEBHOOK_SECRET = defineSecret('STRIPE_WEBHOOK_SECRET');
// Ids de preço da Stripe (públicos, não são segredo): Pro mensal R$ 29,90, Pro anual R$ 239, Empresa R$ 99,90.
const PRICE_PRO_MONTHLY = defineString('OSTIE_PRICE_PRO_MONTHLY', { default: 'price_1UK3SeQhtJy6DehLDT2CELbu' });
const PRICE_PRO_YEARLY = defineString('OSTIE_PRICE_PRO_YEARLY', { default: 'price_1UK3RsQhtJy6DehLY3Xq0dz8' });
const PRICE_BUSINESS = defineString('OSTIE_PRICE_BUSINESS', { default: 'price_1UK3U4QhtJy6DehLGJCH5hM6' });

const prices = () => ({
  proMonthly: PRICE_PRO_MONTHLY.value(),
  proYearly: PRICE_PRO_YEARLY.value(),
  business: PRICE_BUSINESS.value(),
});
const stripe = () => new Stripe(STRIPE_SECRET_KEY.value());
const customers = () => admin.firestore().collection('ostie_clientes');

/** Usuário do login Firebase pelo token enviado pelo app (Authorization: Bearer ...). */
async function userFrom(req) {
  const header = req.get('Authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!token) throw Object.assign(new Error('Entre com a sua conta no OSTIE.'), { status: 401 });
  try {
    return await admin.auth().verifyIdToken(token);
  } catch (_) {
    throw Object.assign(new Error('Login expirado. Entre de novo no OSTIE.'), { status: 401 });
  }
}

/** Cliente da Stripe deste usuário (criado na primeira compra, guardado no Firestore). */
async function customerFor(user) {
  const doc = await customers().doc(user.uid).get();
  if (doc.exists && doc.get('customerId')) return doc.get('customerId');
  const created = await stripe().customers.create({
    email: user.email || undefined,
    metadata: { app: 'ostie', uid: user.uid },
  });
  await customers().doc(user.uid).set({ customerId: created.id, criadoEm: Date.now() }, { merge: true });
  return created.id;
}

function baseUrl(req) {
  return `https://${req.get('host')}`;
}

function fail(res, error) {
  console.error(error);
  res.status(error.status || 500).json({ erro: error.status ? error.message : 'Falha no servidor do OSTIE. Tente de novo.' });
}

/** Abre o checkout da Stripe para um plano (PRO_MENSAL, PRO_ANUAL ou EMPRESA). */
exports.ostieCheckout = onRequest({ secrets: [STRIPE_SECRET_KEY] }, async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ erro: 'Use POST.' });
  try {
    const user = await userFrom(req);
    const option = String((req.body && req.body.plano) || '');
    const price = priceFor(option, prices());
    const customer = await customerFor(user);
    const session = await stripe().checkout.sessions.create({
      mode: 'subscription',
      customer,
      client_reference_id: user.uid,
      line_items: [{ price, quantity: 1 }],
      allow_promotion_codes: true, // cupom de presente para quem já tinha o OSTIE
      metadata: { app: 'ostie', uid: user.uid },
      subscription_data: { metadata: { app: 'ostie', uid: user.uid } },
      success_url: `${baseUrl(req)}/ostieVolta?ok=1`,
      cancel_url: `${baseUrl(req)}/ostieVolta?ok=0`,
      locale: 'pt-BR',
    });
    res.json({ url: session.url });
  } catch (error) {
    if (String(error.message).startsWith('Plano desconhecido')) error.status = 400;
    fail(res, error);
  }
});

/**
 * Portal só do OSTIE (criado uma vez e guardado no Firestore): trocar cartão, ver faturas e cancelar no fim do
 * período. Sem "trocar de plano", para não aparecerem os planos do OSONE, que usa o portal padrão da conta.
 */
async function portalConfiguration() {
  const ref = admin.firestore().collection('ostie_config').doc('portal');
  const saved = await ref.get();
  if (saved.exists && saved.get('id')) return saved.get('id');
  const created = await stripe().billingPortal.configurations.create({
    business_profile: { headline: 'OSTIE: gerencie sua assinatura' },
    features: {
      customer_update: { enabled: true, allowed_updates: ['email'] },
      invoice_history: { enabled: true },
      payment_method_update: { enabled: true },
      subscription_cancel: { enabled: true, mode: 'at_period_end' },
      subscription_update: { enabled: false },
    },
    metadata: { app: 'ostie' },
  });
  await ref.set({ id: created.id, criadoEm: Date.now() });
  return created.id;
}

/** Portal da Stripe do OSTIE: trocar cartão, ver faturas ou cancelar. */
exports.ostiePortal = onRequest({ secrets: [STRIPE_SECRET_KEY] }, async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ erro: 'Use POST.' });
  try {
    const user = await userFrom(req);
    const doc = await customers().doc(user.uid).get();
    if (!doc.exists) return res.status(404).json({ erro: 'Você ainda não tem assinatura do OSTIE.' });
    const session = await stripe().billingPortal.sessions.create({
      customer: doc.get('customerId'),
      configuration: await portalConfiguration(),
      return_url: `${baseUrl(req)}/ostieVolta?ok=1`,
    });
    res.json({ url: session.url });
  } catch (error) {
    fail(res, error);
  }
});

/** Recalcula o plano do cliente e grava no login Firebase (mantendo as declarações do OSONE). */
async function syncPlan(customerId) {
  const customer = await stripe().customers.retrieve(customerId);
  const uid = customer && customer.metadata && customer.metadata.uid;
  if (!uid || customer.metadata.app !== 'ostie') return; // cliente do OSONE: não é com a gente
  const subs = await stripe().subscriptions.list({ customer: customerId, status: 'all', limit: 100 });
  const plan = bestPlan(subs.data, prices());
  const user = await admin.auth().getUser(uid);
  await admin.auth().setCustomUserClaims(uid, mergeClaims(user.customClaims, plan));
  await customers().doc(uid).set({ plano: plan, atualizadoEm: Date.now() }, { merge: true });
  console.log(`OSTIE: ${uid} agora no plano ${plan}`);
}

/** Webhook da Stripe: assinatura criada, mudada, renovada ou cancelada. */
exports.ostieStripeWebhook = onRequest({ secrets: [STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET] }, async (req, res) => {
  let event;
  try {
    event = stripe().webhooks.constructEvent(req.rawBody, req.get('stripe-signature'), STRIPE_WEBHOOK_SECRET.value());
  } catch (error) {
    console.error('Assinatura do webhook inválida', error.message);
    return res.status(400).send('assinatura inválida');
  }
  try {
    const object = event.data.object || {};
    if (event.type.startsWith('customer.subscription.') && object.metadata && object.metadata.app === 'ostie') {
      await syncPlan(object.customer);
    } else if (event.type === 'checkout.session.completed' && object.metadata && object.metadata.app === 'ostie' && object.customer) {
      await syncPlan(object.customer);
    }
    res.json({ recebido: true });
  } catch (error) {
    console.error(error);
    res.status(500).send('falha ao atualizar o plano'); // a Stripe tenta de novo
  }
});

/** Página de volta do checkout: leva de novo ao app. */
exports.ostieVolta = onRequest(async (req, res) => {
  const ok = req.query.ok === '1';
  res.set('Content-Type', 'text/html; charset=utf-8').send(`<!doctype html><html lang="pt-BR"><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><title>OSTIE</title>
<style>body{font-family:sans-serif;background:#0b0b12;color:#eee;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0;padding:16px;text-align:center}
a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:24px;background:#6c5ce7;color:#fff;text-decoration:none;font-weight:600}</style>
</head><body><div><h1>${ok ? 'Pronto!' : 'Tudo bem'}</h1><p>${ok ? 'Sua assinatura do OSTIE foi registrada. Pode levar alguns segundos para liberar.' : 'Nada foi cobrado.'}</p>
<a href="ostie://assinatura">Voltar ao OSTIE</a></div></body></html>`);
});
