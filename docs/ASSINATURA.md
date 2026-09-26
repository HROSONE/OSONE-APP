# Assinatura do OSTIE (Firebase + Stripe)

Planos: Grátis, Pro (R$ 29,90/mês ou R$ 239/ano) e Empresa (R$ 99,90/mês por aparelho).
O app entra com a conta do Firebase do OSONE, o pagamento é pela Stripe (mesma conta do OSONE) e as Cloud Functions em `firebase/ostie-functions` gravam o plano no login (`ostiePlan`).

**Os limites dos planos só ligam quando os segredos do Firebase existem no GitHub.** Faça os passos na ordem: servidor e Stripe primeiro, segredos por último.

## 1. Stripe (modo de teste primeiro)
1. Produtos → **OSTIE Pro** com dois preços recorrentes: R$ 29,90 mensal e R$ 239 anual.
2. Produtos → **OSTIE Empresa**: R$ 99,90 mensal.
3. Anote os três ids de preço (`price_...`).
4. Cupom de presente para quem já tinha o OSTIE: Cupons → 100% por 3 meses → crie um código promocional (ex.: `OSTIEPRESENTE`). O checkout aceita códigos.
5. Portal do cliente (Configurações → Billing → Customer portal): ative cancelar e trocar de plano entre os produtos do OSTIE.

## 2. Firebase (projeto do OSONE)
1. O projeto precisa do plano **Blaze** (Cloud Functions) e do **Firestore** ativo.
2. Configurações do projeto → Seus apps → **Adicionar app Android** com o pacote `com.osone.app`.
3. Adicione a **SHA-1 e a SHA-256** da assinatura do OSTIE. Elas aparecem no log da CI da `main`, no passo "Compilar APK com assinatura permanente" (linhas `SHA1:` e `SHA256:`).
4. Authentication → Método de login: ative **Google** e **E-mail/senha**.
5. Anote: ID do projeto, **ID do app Android** (`1:...:android:...`), **chave da API da Web** e o **ID do cliente Web** (Authentication → Google → Configuração do SDK da Web).

## 3. Publicar as funções
1. Na Stripe, Desenvolvedores → Webhooks → Adicionar endpoint (o endereço já é conhecido antes do deploy):
   `https://us-central1-ID_DO_PROJETO.cloudfunctions.net/ostieStripeWebhook`
   com os eventos `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated` e
   `customer.subscription.deleted`. Copie o segredo de assinatura (`whsec_...`).
2. No computador, com o Firebase CLI logado na conta do OSONE:
```
cd firebase/ostie-functions && npm install && cd ..
firebase functions:secrets:set STRIPE_SECRET_KEY --project ID_DO_PROJETO
firebase functions:secrets:set STRIPE_WEBHOOK_SECRET --project ID_DO_PROJETO
firebase deploy --only functions:ostie --project ID_DO_PROJETO
```
O deploy pergunta os ids de preço; os do OSTIE já vêm preenchidos (é só apertar Enter).
O codebase `ostie` é separado: as funções do OSONE não são tocadas. Os segredos são digitados no terminal e ficam no
Secret Manager do Google; nunca no código nem no chat.

## 4. Segredos no GitHub (liga os planos)
Settings → Secrets and variables → Actions → New repository secret:
- `OSTIE_FIREBASE_PROJECT_ID`
- `OSTIE_FIREBASE_APP_ID`
- `OSTIE_FIREBASE_API_KEY`
- `OSTIE_FIREBASE_WEB_CLIENT_ID`

A próxima versão publicada já sai com login, botão Assinar e limites do plano Grátis.

## 5. Conferir
Com a Stripe em modo de teste: entrar no OSTIE, Ajustes → Seu plano → Assinar Pro, pagar com o cartão de teste `4242 4242 4242 4242`, tocar em "Voltar ao OSTIE" e ver "Plano atual: Pro". Depois troque para as chaves reais da Stripe.
