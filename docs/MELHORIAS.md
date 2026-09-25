# Melhorias do OSTIE

Backlog mantido pelo comando `/aprimorar`. Cada sessão lê este arquivo antes de analisar o app e o atualiza no fim.

## Pendentes

Do plano de 25/09/2026 (tarde), ficaram:

- Rotina ao ligar o carregador (no máximo uma vez por dia).
- Formulário de rotina com o tipo de gatilho (horário, notificação, carregador); hoje as de notificação são criadas pelo chat ou Live.

Ideias maiores para depois:

- **Busca que sobreviva a 2027**: o Google encerra a Custom Search JSON API em 01/01/2027. Candidatas grátis sem cartão: Tavily (1.000 créditos/mês) e Exa; Serper tem 2.500 buscas únicas (resultados do Google). `GoogleSearchApi` e `WebSearch` já isolam o provedor.

- **Rotina ao chegar em casa (Wi-Fi)**: exige permissão de localização para ler o nome da rede.
- **Pular a busca do Google quando a cota do dia acabar** (o usuário disse que por ora não atrapalha).

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
| 0.15.92 | Ler páginas (read_url), Tavily como segunda busca, cache de 10 min, fontes na tela do Live, Live continua o chat, aviso "Pesquisando…", reconexão sem cortar a fala e correção dos engasgos de voz nos modelos 3.x |
| 0.15.95 | Chat com botão Parar, menu da mensagem (copiar, compartilhar, tentar de novo), formatação (negrito, listas, títulos) e ditado por voz; rotinas que disparam ao chegar notificação |
| 0.15.97 | "Ei, Ostie" volta a baixar o reconhecedor: aceita o modelo de voz no formato antigo (pt-0.3) e avisa download interrompido |
