# Assinatura do OSTIE (Firebase Blaze + Stripe)

Planos: Grátis, Pro (R$ 29,90/mês ou R$ 239/ano) e Empresa (R$ 99,90/mês por aparelho).
O app entra com a conta do Firebase do OSONE (projeto `osone-agi`), o pagamento é pela Stripe (mesma conta do OSONE)
e as Cloud Functions em `firebase/ostie-functions` (codebase `ostie`, separado do OSONE) gravam o plano no login (`ostiePlan`).

**Os limites dos planos só ligam quando os segredos existem no GitHub.** Faça os passos na ordem; segredos por último.

## 1. Stripe (feito em 26/09/2026)
- Produtos OSTIE Pro (mensal `price_1UK3Se…CELbu`, anual `price_1UK3Rs…0dz8`) e OSTIE Empresa (`price_1UK3U4…5hM6`), já no código.
- Cupom `OSTIEPRESENTE`: 100% por 3 meses, só no OSTIE Pro, primeiro pedido, 22 usos.
- Portal do cliente: **não mexer** no portal padrão (é o do OSONE). As funções criam um portal só do OSTIE.

## 2. Firebase `osone-agi`
- Feito: app Android `com.osone.app` (OSTIE) com SHA-1 e SHA-256; login Google e E-mail/senha ativos.
- **Plano Blaze**: Firebase → ícone de engrenagem → Uso e faturamento → Detalhes e configurações → Modificar plano → Blaze
  (vincula uma conta de faturamento do Google Cloud com cartão). Na mesma tela, crie um **alerta de orçamento** (ex.: R$ 10).
  O uso do OSTIE cabe na cota gratuita mensal; o alerta avisa se algo fugir disso.

## 3. Webhook da Stripe
Desenvolvedores → Webhooks → endpoint
`https://us-central1-osone-agi.cloudfunctions.net/ostieStripeWebhook` com os eventos
`checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`.
Guarde o segredo `whsec_...` para o passo 4 (erros de envio antes do deploy são normais).

## 4. Publicar as funções (Google Cloud Shell, no navegador)
console.cloud.google.com → projeto `osone-agi` → ícone `>_` (Ativar Cloud Shell). Com o repositório público (ou já na `main`):
```
git clone https://github.com/HROSONE/OSONE-APP.git
cd OSONE-APP/firebase/ostie-functions && npm install && cd ..
npm install -g firebase-tools
firebase login --no-localhost
firebase functions:secrets:set STRIPE_SECRET_KEY --project osone-agi
firebase functions:secrets:set STRIPE_WEBHOOK_SECRET --project osone-agi
firebase deploy --only functions:ostie --project osone-agi
```
Os segredos (`sk_live_...` e `whsec_...`) são colados só no terminal; ficam no Secret Manager do Google, nunca no código nem no chat.
O deploy pergunta os ids de preço já preenchidos: aperte Enter. As funções do OSONE não são tocadas.

## 5. Segredos no GitHub (liga login, assinatura e limites)
OSONE-APP → Settings → Secrets and variables → Actions → New repository secret:
- `OSTIE_FIREBASE_PROJECT_ID` = `osone-agi`
- `OSTIE_FIREBASE_APP_ID` = ID do app Android (`1:997519616556:android:...`)
- `OSTIE_FIREBASE_API_KEY` = Configurações do projeto → Geral → Chave de API da Web (`AIza...`)
- `OSTIE_FIREBASE_WEB_CLIENT_ID` = Authentication → Google → Configuração do SDK da Web → ID do cliente da Web

## 6. Conferir
Com o próprio cartão e o cupom `OSTIEPRESENTE` (nada é cobrado): Ajustes → Seu plano → entrar → Assinar Pro mensal →
"Voltar ao OSTIE" → "Plano atual: Pro". Depois cancele em Gerenciar assinatura (volta ao Grátis no fim do período).
