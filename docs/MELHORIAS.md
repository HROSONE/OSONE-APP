# Melhorias do OSTIE

Backlog mantido pelo comando `/aprimorar`. Cada sessão lê este arquivo antes de analisar o app e o atualiza no fim.

## Pendentes

Nada pendente do plano de 25/09/2026 (saiu na 0.15.80).

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
