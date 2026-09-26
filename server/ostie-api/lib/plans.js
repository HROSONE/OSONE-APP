// Regras dos planos no servidor (sem dependências, testáveis com `node --test`).
// O app lê o plano pela declaração "ostiePlan" do login Firebase; aqui decidimos qual gravar.

const ORDER = ['GRATIS', 'PRO', 'EMPRESA'];

/** Opções vendidas no app → preço da Stripe (ids vêm da configuração da função). */
function priceFor(option, prices) {
  const map = { PRO_MENSAL: prices.proMonthly, PRO_ANUAL: prices.proYearly, EMPRESA: prices.business };
  const price = map[option];
  if (!price) throw new Error('Plano desconhecido: ' + option);
  return price;
}

/** Preço da Stripe → plano do OSTIE. */
function planForPrice(priceId, prices) {
  if (priceId && priceId === prices.business) return 'EMPRESA';
  if (priceId && (priceId === prices.proMonthly || priceId === prices.proYearly)) return 'PRO';
  return 'GRATIS';
}

/**
 * Melhor plano entre as assinaturas do cliente: só as do OSTIE (metadata.app = "ostie", para as do OSONE
 * não liberarem nada por engano) e só as ativas ou em teste.
 */
function bestPlan(subscriptions, prices) {
  let best = 'GRATIS';
  for (const sub of subscriptions || []) {
    if (!sub || !sub.metadata || sub.metadata.app !== 'ostie') continue;
    if (sub.status !== 'active' && sub.status !== 'trialing') continue;
    for (const item of (sub.items && sub.items.data) || []) {
      const plan = planForPrice(item.price && item.price.id, prices);
      if (ORDER.indexOf(plan) > ORDER.indexOf(best)) best = plan;
    }
  }
  return best;
}

/** Declarações do login com o plano novo, sem apagar as que o OSONE já usa. */
function mergeClaims(existing, plan) {
  return Object.assign({}, existing || {}, { ostiePlan: plan });
}

module.exports = { priceFor, planForPrice, bestPlan, mergeClaims, ORDER };
