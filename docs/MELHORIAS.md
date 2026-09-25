# Melhorias do OSTIE

Backlog mantido pelo comando `/aprimorar`. Cada sessão lê este arquivo antes de analisar o app e o atualiza no fim.

## Pendentes

Programado na branch `claude/aprimorar-71toz4` (lotes 1, 2 e 3 do plano de 25/09/2026). **Falta compilar e publicar**: os minutos do GitHub Actions acabaram; quando voltarem, abrir um PR único com tudo, corrigir o que a CI apontar e publicar. Itens:

1. Ações no chat escrito com Groq e OpenRouter (ferramentas no formato OpenAI, até 5 rodadas; responde sem elas se o modelo recusar).
2. Rotinas com Groq/OpenRouter leem agenda e notificações e deixam botões.
3. Rotina que falha depois de usar ferramenta não é repetida.
4. Live relê a memória a cada conexão.
5. Editar rotina existente.
6. Cada rotina mostra quando roda de novo.
7. Rotinas no horário exato: `USE_EXACT_ALARM` (Android 13+) e aviso com botão "Liberar" quando o Android não permite.
8. Botão "Copiar" nos últimos resultados das rotinas.
9. Contexto do chat por tamanho de texto (`ChatContext`): Gemini até 40 mensagens/60 mil caracteres; Groq e OpenRouter até 20/12 mil.
10. Widget na tela inicial: botão Falar e próxima rotina (abre a aba Rotinas).
11. Testes de tela do Live (relógio manual por causa do orbe) e dos Ajustes, e da edição de rotina.
12. Aba de Escrita com campo de pedido: o modelo de texto escreve algo novo ou altera só o que foi pedido no documento atual; botão "Desfazer" (também depois de apagar).
13. API de busca do Google (Custom Search: chave do Cloud Console + cx) em Ajustes > Pesquisa Google, com botão Testar; chat, Live e rotinas pesquisam por ela e usam o Gemini se ela falhar.

Ideias maiores para depois:

- **Busca que sobreviva a 2027**: o Google encerra a Custom Search JSON API em 01/01/2027. Candidatas grátis sem cartão: Tavily (1.000 créditos/mês) e Exa; Serper tem 2.500 buscas únicas (resultados do Google). `GoogleSearchApi` e `WebSearch` já isolam o provedor.

- **Rotinas por evento**: disparar ao carregar, ao chegar em casa (Wi-Fi) ou ao receber notificação de um app, além do horário.

## Feitos

| Versão | O que mudou |
| --- | --- |
| 0.15.58 | Live 3.x volta a funcionar (degraus de configuração por modelo), preview da Aba de Escrita corrigido |
| 0.15.60 | Pesquisa pelo modelo de texto no Live 3.x, retomada de sessão, legendas e conversa de voz no chat, CI mais econômica |
| 0.15.62 | Ações no chat escrito, rotinas que leem a agenda e deixam botões na notificação, memória que se organiza, eco calibrado por aparelho, chave do Live no cabeçalho |
| 0.15.66 | Testes de tela com Robolectric; checagem da pasta de memória não quebra sem armazenamento externo |
| 0.15.68 | Canal de atualizações no nome de usuário novo (`HROSONE`), com o antigo como reserva |
| 0.15.70 | Escuta ativa: "Ei, Ostie" abre o Live de qualquer tela (Vosk offline) |
| 0.15.76 | Respostas do chat faladas com Gemini 3.8 Flash TTS / Flash-Lite TTS; repositório de releases renomeado para `OSTIE-AI-releases` |
| próxima | Base de conhecimento (texto, link, PDF, MD, TXT, DOCX) para o OSTIE atender sobre uma empresa, produto ou assunto |
