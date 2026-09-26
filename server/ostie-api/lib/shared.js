// Peças comuns das rotas do servidor do OSTIE (Vercel): Stripe, Firebase Admin, login e respostas.
// Segredos só em variáveis de ambiente do projeto na Vercel, nunca no código.
const Stripe = require('stripe');
const admin = require('firebase-admin');

/** Ids de preço da Stripe (públicos): Pro mensal R$ 29,90, Pro anual R$ 239, Empresa R$ 99,90. */
const prices = {
  proMonthly: process.env.OSTIE_PRICE_PRO_MONTHLY || 'price_1UK3SeQhtJy6DehLDT2CELbu',
  proYearly: process.env.OSTIE_PRICE_PRO_YEARLY || 'price_1UK3RsQhtJy6DehLY3Xq0dz8',
  business: process.env.OSTIE_PRICE_BUSINESS || 'price_1UK3U4QhtJy6DehLGJCH5hM6',
};

let stripeClient;
function stripe() {
  if (!stripeClient) stripeClient = new Stripe(process.env.STRIPE_SECRET_KEY);
  return stripeClient;
}

/** Firebase Admin com a conta de serviço do projeto (variável FIREBASE_SERVICE_ACCOUNT, JSON inteiro). */
function firebase() {
  if (!admin.apps.length) {
    const account = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT || '{}');
    admin.initializeApp({ credential: admin.credential.cert(account) });
  }
  return admin;
}

const customers = () => firebase().firestore().collection('ostie_clientes');

/** Usuário do login Firebase pelo token enviado pelo app (Authorization: Bearer ...). */
async function userFrom(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!token) throw Object.assign(new Error('Entre com a sua conta no OSTIE.'), { status: 401 });
  try {
    return await firebase().auth().verifyIdToken(token);
  } catch (_) {
    throw Object.assign(new Error('Login expirado. Entre de novo no OSTIE.'), { status: 401 });
  }
}

/** Corpo JSON do pedido (a Vercel já entrega lido quando o tipo é application/json). */
function jsonBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  try { return JSON.parse(req.body || '{}'); } catch (_) { return {}; }
}

function baseUrl(req) {
  return `https://${req.headers['x-forwarded-host'] || req.headers.host}`;
}

function fail(res, error) {
  console.error(error);
  res.status(error.status || 500).json({ erro: error.status ? error.message : 'Falha no servidor do OSTIE. Tente de novo.' });
}

module.exports = { prices, stripe, firebase, customers, userFrom, jsonBody, baseUrl, fail };
