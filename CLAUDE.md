# OSTIE (OSONE-APP)

Assistente pessoal Android em Kotlin + Jetpack Compose (pacote `com.osone.app`). Conversa por voz com o Gemini Live (WebSocket), chat escrito (Gemini, Groq, OpenRouter), Aba de Escrita com preview HTML/SVG, rotinas agendadas, memória própria em `Documentos/OSTIE/memoria.md` e ações no celular (alarmes, agenda, mensagens prontas, notificações, acessibilidade).

Converse com o usuário em português do Brasil, em linguagem simples. Para melhorar o app sem um pedido específico, use o comando `/aprimorar` (`.claude/skills/aprimorar/SKILL.md`). O backlog fica em `docs/MELHORIAS.md`.

## Mapa do código (`app/src/main/java/com/osone/app/`)

- `MainActivity.kt`: navegação entre telas, permissões e intents.
- Telas: `ChatScreen`, `LiveScreen` (painel do Live), `WritingScreen`, `RoutinesScreen`, `SettingsScreen`; componentes e tema em `OstieUi.kt`, ícones em `OstieIcons.kt`.
- Live: `LiveVoiceViewModel` (setup, níveis de configuração adaptativos, retomada, legendas, ferramentas), `LiveAudioEngine` + `EchoCalibration` (áudio e eco), `LiveModels`, `LiveCloseReason`, `LiveExtras` (web_search: API de busca do Google em `GoogleSearchApi` ou Gemini; transcrição para o chat), `LiveSessionService`.
- Texto: `GeminiClient` (SSE, pesquisa Google, chamadas de função em laço), `ChatCompletionClient` (Groq e OpenRouter, também com ferramentas; formato em `OpenAiTools`), `ChatContext` (quantas mensagens anteriores vão ao modelo), `TextModel` (modelo "cérebro" e combinações de ferramentas), `OsoneViewModel` (chat), `ChatHistory` (conversas anteriores e busca, guardadas por `ConversationStore`), `ImageGen` (imagens do Gemini), `JevApi` + `JevDecisions` (Jev da TypeSafe: decide pesquisa, ações e notificações; opcional, interruptor desligado por padrão).
- Ações: `PhoneActions` (intents diretas), `AndroidLocalTools` (apps, ajustes, acessibilidade), `AgentTools` (ferramentas para chat e rotinas).
- Visão de tela: `OsoneAccessibilityService.screenshot` (print com marcas numeradas e grade em pixels reais, `ScreenMarks`; toque pela marca em `tap_mark`), `GesturePlan` (gestos com duração), `ScreenCaptureController` + `FrameChange` (compartilhamento que pula quadros iguais), `EditorGuides` (guias de editores de vídeo para o agente).
- Memória e rotinas: `MemoryStore`, `MemoryOrganizer`, `Routines` (agenda pura em `RoutineSchedule`), `UserProfile`.
- Primeiro uso: `WelcomeScreen`. Cópia de segurança: `SettingsBackup` (ajustes e rotinas, sem chaves).
- Escuta "Ei, Ostie": `WakeWord` (Vosk) e `BatteryPolicy` (pausa com bateria fraca).
- Tela inicial: `OstieWidget` (Falar e próxima rotina), `OstieTileService`, atalhos em `res/xml/shortcuts.xml`.
- Atualização: `AppUpdater` (`UpdateFeed`), `UpdateWork`.

## Validar sem SDK Android

O ambiente não tem SDK Android (dl.google.com é bloqueado); quem compila é a CI. Antes de enviar:

1. Lógica pura (sem classes Android) ganha teste JVM em `app/src/test/java/com/osone/app/`.
2. Rode esses testes num projeto Gradle JVM no scratchpad: `build.gradle.kts` com `kotlin("jvm") version "2.1.0"`, `org.json:json:20250517` e `junit:junit:4.13.2`; copie para `src/main/kotlin` apenas os arquivos puros envolvidos (ex.: `DocumentPreview.kt`, `EchoCalibration.kt`) e os testes para `src/test/kotlin`; rode `gradle test --offline`.
3. Testes de tela usam Robolectric (SDK 34) em `ScreensTest.kt` e só rodam na CI. Em Robolectric não há armazenamento externo; código que toca `Environment` precisa tolerar exceções.
4. Releia o diff procurando erros de compilação antes do push: cada push vermelho gasta minutos de CI.

## CI e publicação

- `.github/workflows/android.yml`: PR roda testes e compila o APK de teste; push na `main` compila o APK assinado e publica.
- Publicação: pré-lançamento `ostie-v<versão>` e release fixo `ostie-latest` com `latest.json` no repositório público `HROSONE/OSTIE-AI-releases`. O app lê `https://github.com/HROSONE/OSTIE-AI-releases/releases/download/ostie-latest/latest.json` (com os endereços antigos, `HROSONE/OSONE-AI-releases` e `zerobob623-bit/OSONE-AI-releases`, como reserva).
- `versionCode` = 100 + número da execução; `versionName` = `0.15.<versionCode - 100>`.
- Depois de mesclar, confira o `latest.json` (versão nova) e o SHA-256 do APK publicado.
- Minutos de CI são limitados enquanto o repositório é privado: agrupe mudanças num PR e siga o protocolo "janela pública" abaixo; mudanças só em `*.md` e `docs/` não rodam CI.

## Protocolo "janela pública" (minutos de CI)

Repositório privado tem cota de minutos do Actions; público não tem limite. Então o `OSONE-APP` fica **privado** e só abre durante a CI de um lote grande:

1. Acumule as mudanças na branch da sessão **sem abrir PR** (push em branch não roda CI).
2. Antes de pedir a abertura: rode os testes JVM, releia o diff e confira que nada sensível entrou no histórico (`git grep` por `AIza`, `gsk_`, `sk-or-`, `ghp_`, `BEGIN PRIVATE KEY`, arquivos `.jks`/`.keystore`/`.env`).
3. Peça ao usuário: **Settings → General → Danger Zone → Change visibility → Public** (Claude não consegue mudar a visibilidade).
4. Com o repositório público: abra o PR, acompanhe a CI até ficar verde, mescle sem pedir confirmação (o usuário autorizou em 25/09/2026), espere a publicação na `main` e confira `latest.json` e o SHA-256 do APK.
5. Avise na hora para voltar a **Private** (mesmo caminho). A janela deve durar só o necessário (cerca de 15 a 30 minutos).
6. Se algo travar no meio, fechar o repositório antes de continuar: a branch fica guardada e a CI é retomada na próxima janela.

Enquanto está público, qualquer pessoa pode ver e copiar o código (e o e-mail dos commits); cópias (forks) feitas nessa janela continuam públicas depois. Por isso nada de segredos no repositório, nunca.

## Regras

- Nunca grave chaves de API, tokens ou dados pessoais em arquivos, commits ou logs.
- Não apague memória, rotinas ou conversa do usuário sem migração ou cópia.
- Textos da interface em português do Brasil; ícones sem rótulo precisam de `contentDescription`.
