// POST /api/checkout {plano: PRO_MENSAL | PRO_ANUAL | EMPRESA} → {url} do checkout da Stripe.
const { prices, stripe, customers, userFrom, jsonBody, baseUrl, fail } = require('../lib/shared');
const { priceFor } = require('../lib/plans');

/** Cliente da Stripe deste usuário (criado na primeira compra, guardado no Firestore). */
async function customerFor(user) {
  const doc = await customers().doc(user.uid).get();
  if (doc.exists && doc.get('customerId')) return doc.get('customerId');
  const created = await stripe().customers.create({ email: user.email || undefined, metadata: { app: 'ostie', uid: user.uid } });
  await customers().doc(user.uid).set({ customerId: created.id, criadoEm: Date.now() }, { merge: true });
  return created.id;
}

module.exports = async (req, res) => {
  if (req.method !== 'POST') return res.status(405).json({ erro: 'Use POST.' });
  try {
    const user = await userFrom(req);
    let price;
    try { price = priceFor(String(jsonBody(req).plano || ''), prices); }
    catch (error) { error.status = 400; throw error; }
    const session = await stripe().checkout.sessions.create({
      mode: 'subscription',
      customer: await customerFor(user),
      client_reference_id: user.uid,
      line_items: [{ price, quantity: 1 }],
      allow_promotion_codes: true, // cupom de presente para quem já tinha o OSTIE
      metadata: { app: 'ostie', uid: user.uid },
      subscription_data: { metadata: { app: 'ostie', uid: user.uid } },
      success_url: `${baseUrl(req)}/api/volta?ok=1`,
      cancel_url: `${baseUrl(req)}/api/volta?ok=0`,
      locale: 'pt-BR',
    });
    res.json({ url: session.url });
  } catch (error) {
    fail(res, error);
  }
};
