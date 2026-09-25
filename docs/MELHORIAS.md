# Melhorias do OSTIE

Backlog mantido pelo comando `/aprimorar`. Cada sessão lê este arquivo antes de analisar o app e o atualiza no fim.

## Pendentes

Nada pendente do plano de 25/09/2026 (saiu na 0.15.80).

- Ideia: mostrar Markdown no balão do chat (negrito, listas, tabelas) em vez de proibir.

Plano "pesquisa e Live" (25/09/2026, feito na branch, aguardando a janela pública):

Lote A — pesquisa
1. read_url: o OSTIE abre um link dos resultados e lê o texto da página (chat e Live).
2. Pesquisa mais rápida: cache de 10 min por consulta, espera menor no Gemini e data das páginas nos resultados da API do Google.
3. Tavily como segunda opção de busca (grátis, 1.000/mês, sem cartão): Google API → Tavily → Gemini.
4. Fontes na tela do Live: links das pesquisas viram botões que abrem no navegador.

Lote B — Live
5. Live continua a conversa do chat: as últimas mensagens entram nas instruções ao iniciar.
6. Status visível enquanto ferramentas rodam ("Pesquisando na web…", "Mexendo no celular…").
7. Reconexão sem cortar a fala: no aviso de troca de servidor (goAway), espera o fim da frase antes de reconectar.

Engasgos no Live 3.x (investigado): fila de áudio de 96 pedaços descartava rajadas; generationComplete encerrava a fala antes do fim do áudio; sem sensibilidade de voz baixa, o eco cortava a fala. Corrigido na mesma branch; o diagnóstico passa a mostrar "faltas" e "descartes" de áudio ao fim da conversa.

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
| 0.15.78 | Base de conhecimento (texto, link, PDF, MD, TXT, DOCX) para o OSTIE atender sobre uma empresa, produto ou assunto |
| 0.15.80 | Ações no chat e nas rotinas com Groq e OpenRouter, rotinas editáveis e no horário exato, contexto maior no chat, widget na tela inicial, pedidos digitados na Aba de Escrita com "Desfazer", API de busca do Google, testes de tela do Live e dos Ajustes |
| 0.15.82 | Chat e Live sabem a data de hoje; Groq e OpenRouter pesquisam na web antes de falar de notícias, preços, clima etc.; respostas do chat sem Markdown cru |
| 0.15.84 | Controle do celular com pinça (ampliar/reduzir), toque duplo, segurar e arrastar segurando |
| 0.15.86 | Agente espera a tela, vê a tela (print), rola até achar, escolhe entre botões repetidos, pede o sim antes de enviar/pagar/apagar, copia e cola, abre painéis rápidos e mostra o histórico de ações |
| 0.15.88 | Agente mais rápido: tela nova junto com cada ação, sequências de até 10 passos numa chamada, gestos e esperas mais curtos, Live com raciocínio baixo e sem narrar cada passo |
| 0.15.90 | Chat entende "pesquisa"/"busca na internet" (usa a pergunta anterior), mostra por que a pesquisa falhou e, no Groq com GPT OSS, tenta a pesquisa própria do Groq |
