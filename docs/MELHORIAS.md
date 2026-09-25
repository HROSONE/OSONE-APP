# Melhorias do OSTIE

Backlog mantido pelo comando `/aprimorar`. Cada sessão lê este arquivo antes de analisar o app e o atualiza no fim.

## Pendentes

Plano da sessão de 25/09/2026 (da mais valiosa para a menos), em lotes de um PR cada:

**Lote 1 — o OSTIE age com qualquer "cérebro"**
1. Ações no chat escrito com Groq e OpenRouter: chamadas de ferramenta no formato OpenAI (streaming), laço de até 5 rodadas, conversão do esquema Gemini para JSON Schema e volta sem ferramentas se o modelo recusar.
2. Rotinas com Groq/OpenRouter também leem agenda e notificações e deixam botões na notificação (hoje só o Gemini usa ferramentas nelas).
3. Rotina que falha depois de usar uma ferramenta não é repetida (evita anotar duas vezes na memória).
4. Live relê a memória ao reconectar (pega anotações e organizações feitas no meio da conversa).

**Lote 2 — rotinas mais fáceis e pontuais**
5. Editar rotina existente (hoje só dá para apagar e criar de novo).
6. Cada rotina mostra quando roda de novo ("amanhã 08:00").
7. Aviso com botão para liberar "Alarmes e lembretes" no Android 12+: sem isso a rotina pode atrasar até 10 min.
8. Botão "Copiar" nos últimos resultados.

**Lote 3 — chat e tela inicial**
9. Contexto maior no chat: enviar as últimas mensagens por tamanho de texto, não só as 12 últimas.
10. Widget na tela inicial: iniciar o Live e ver a próxima rotina.
11. Testes de tela do Live e dos Ajustes (controlar as animações infinitas com `mainClock.autoAdvance = false`).

Ideias maiores para depois:

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
