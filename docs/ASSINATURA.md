# Assinatura do OSTIE (Firebase + Stripe + Vercel)

Planos: Grátis, Pro (R$ 29,90/mês ou R$ 239/ano) e Empresa (R$ 99,90/mês por aparelho).
O app entra com a conta do Firebase do OSONE (projeto `osone-agi`), o pagamento é pela Stripe (mesma conta do OSONE)
e o servidor em `server/ostie-api` (projeto separado na Vercel, como o OSONE) grava o plano no login (`ostiePlan`).
Não precisa do plano Blaze do Firebase.

**Os limites dos planos só ligam quando os segredos existem no GitHub.** Faça os passos na ordem; segredos por último.

## 1. Stripe (feito em 26/09/2026)
- Produtos OSTIE Pro (mensal `price_1UK3Se…CELbu`, anual `price_1UK3Rs…0dz8`) e OSTIE Empresa (`price_1UK3U4…5hM6`), já no código.
- Cupom `OSTIEPRESENTE`: 100% por 3 meses, só no OSTIE Pro, primeiro pedido, 22 usos.
- Portal do cliente: **não mexer** no portal padrão (é o do OSONE). O servidor cria um portal só do OSTIE.

## 2. Firebase (`osone-agi`)
- App Android `com.osone.app` (apelido OSTIE) com SHA-1 e SHA-256: feito.
- Login com Google e E-mail/senha: já ativos.
- Falta: Configurações do projeto → **Contas de serviço** → **Gerar nova chave privada**. Baixa um arquivo `.json`:
  é uma senha do projeto inteiro; não mande a ninguém, não suba no GitHub, só cole na Vercel (passo 3).

## 3. Vercel (projeto novo, separado do OSONE)
1. vercel.com → Add New → Project → importe o repositório **OSONE-APP** (dê acesso ao repositório se pedir).
2. **Root Directory**: `server/ostie-api`. Framework: **Other**. Nome sugerido: `ostie-api`.
3. Environment Variables:
   - `STRIPE_SECRET_KEY` = chave secreta da Stripe (`sk_live_...`)
   - `FIREBASE_SERVICE_ACCOUNT` = conteúdo inteiro do `.json` da conta de serviço
   - `STRIPE_WEBHOOK_SECRET` = pode ficar vazio agora (passo 4)
4. Deploy. Anote o endereço (ex.: `https://ostie-api.vercel.app`). Teste: `https://ENDERECO/api/volta?ok=1` mostra "Pronto!".

## 4. Webhook da Stripe
Desenvolvedores → Webhooks → endpoint `https://ENDERECO/api/webhook` com os eventos
`checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`.
Copie o segredo `whsec_...`, cole em `STRIPE_WEBHOOK_SECRET` na Vercel e faça **Redeploy**.
(Se criou antes um endpoint com `cloudfunctions.net`, apague-o.)

## 5. Segredos no GitHub (liga login, assinatura e limites)
OSONE-APP → Settings → Secrets and variables → Actions → New repository secret:
- `OSTIE_FIREBASE_PROJECT_ID` = `osone-agi`
- `OSTIE_FIREBASE_APP_ID` = ID do app Android (`1:997519616556:android:...`)
- `OSTIE_FIREBASE_API_KEY` = Configurações do projeto → Geral → Chave de API da Web (`AIza...`)
- `OSTIE_FIREBASE_WEB_CLIENT_ID` = Authentication → Google → Configuração do SDK da Web → ID do cliente da Web
- `OSTIE_API_URL` = endereço da Vercel (ex.: `https://ostie-api.vercel.app`)

## 6. Conferir
Com o próprio cartão e o cupom `OSTIEPRESENTE` (nada é cobrado): Ajustes → Seu plano → entrar → Assinar Pro mensal →
"Voltar ao OSTIE" → "Plano atual: Pro". Depois cancele em Gerenciar assinatura e confira a volta ao Grátis no fim do período.

Obs.: o plano grátis (Hobby) da Vercel é para uso pessoal/não comercial; com vendas, a Vercel pede o plano Pro.
