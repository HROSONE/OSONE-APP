// POST /api/portal → {url} do portal da Stripe só do OSTIE (cancelar, trocar cartão, faturas).
const { stripe, firebase, customers, userFrom, baseUrl, fail } = require('../lib/shared');

/**
 * Portal próprio (criado uma vez e guardado no Firestore), sem "trocar de plano": o portal padrão da conta
 * é o do OSONE e mostraria os planos dele.
 */
async function portalConfiguration() {
  const ref = firebase().firestore().collection('ostie_config').doc('portal');
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

module.exports = async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ erro: 'Use POST.' });
  try {
    const user = await userFrom(req);
    const doc = await customers().doc(user.uid).get();
    if (!doc.exists) return res.status(404).json({ erro: 'Você ainda não tem assinatura do OSTIE.' });
    const session = await stripe().billingPortal.sessions.create({
      customer: doc.get('customerId'),
      configuration: await portalConfiguration(),
      return_url: `${baseUrl(req)}/api/volta?ok=1`,
    });
    res.json({ url: session.url });
  } catch (error) {
    fail(res, error);
  }
};
