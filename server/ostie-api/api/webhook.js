// POST /api/webhook (Stripe): assinatura criada, mudada, renovada ou cancelada → plano no login Firebase.
const { prices, stripe, firebase, customers } = require('../lib/shared');
const { bestPlan, mergeClaims } = require('../lib/plans');

/** Corpo cru (a assinatura da Stripe é conferida sobre os bytes exatos). */
function rawBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/** Recalcula o plano do cliente e grava no login (mantendo as declarações do OSONE). */
async function syncPlan(customerId) {
  const customer = await stripe().customers.retrieve(customerId);
  const uid = customer && customer.metadata && customer.metadata.uid;
  if (!uid || customer.metadata.app !== 'ostie') return; // cliente do OSONE: não é com a gente
  const subs = await stripe().subscriptions.list({ customer: customerId, status: 'all', limit: 100 });
  const plan = bestPlan(subs.data, prices);
  const user = await firebase().auth().getUser(uid);
  await firebase().auth().setCustomUserClaims(uid, mergeClaims(user.customClaims, plan));
  await customers().doc(uid).set({ plano: plan, atualizadoEm: Date.now() }, { merge: true });
  console.log(`OSTIE: ${uid} agora no plano ${plan}`);
}

const handler = async (req, res) => {
  if (req.method !== 'POST') return res.status(405).send('Use POST.');
  let event;
  try {
    event = stripe().webhooks.constructEvent(await rawBody(req), req.headers['stripe-signature'], process.env.STRIPE_WEBHOOK_SECRET);
  } catch (error) {
    console.error('Assinatura do webhook inválida', error.message);
    return res.status(400).send('assinatura inválida');
  }
  try {
    const object = event.data.object || {};
    const ours = object.metadata && object.metadata.app === 'ostie';
    if (ours && (event.type.startsWith('customer.subscription.') || event.type === 'checkout.session.completed') && object.customer) {
      await syncPlan(object.customer);
    }
    res.json({ recebido: true });
  } catch (error) {
    console.error(error);
    res.status(500).send('falha ao atualizar o plano'); // a Stripe tenta de novo
  }
};

module.exports = handler;
// A Vercel não pode ler o corpo antes: a Stripe assina os bytes originais.
module.exports.config = { api: { bodyParser: false } };
