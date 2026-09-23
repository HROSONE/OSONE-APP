# OSONE APP

Projeto **Android nativo** em Kotlin e Jetpack Compose, separado do [OSONE-AI-code](https://github.com/zerobob623-bit/OSONE-AI-code). Ainda não é uma cópia integral dos recursos do desktop.

## Funciona agora

- Conversa por texto com Gemini via HTTPS, usando uma chave individual configurada no aparelho.
- Histórico local de até 100 mensagens e contexto das últimas 12 mensagens por chamada.
- Botão 🎙️ Live junto ao campo de texto abre um orbe central reativo ao volume de entrada e à resposta. A conversa usa PCM bidirecional via WebSocket Gemini Live, com interrupção da fala, sem transcrição e sem sintetizador do Android. O chat escrito continua acessível ao voltar.
- Seleção entre `gemini-3.8-live`, `gemini-3.1-flash-live-preview` e `gemini-2.5-flash-native-audio-preview-12-2025`, com fallback automático opcional. A configuração do Live usa `setup.generationConfig.responseModalities`; o app trata quadros WebSocket binários e de texto e exibe o código/categoria de falha de conexão sem revelar a chave.
- Leitura opcional das respostas do **chat escrito** com TextToSpeech do Android.
- Chave criptografada com Android Keystore e AES-GCM; histórico em armazenamento privado do app. Chaves não entram no Git nem no APK.
- Escolha do cérebro do chat entre Gemini 3.8, 3.7, 3.6, 3.5 e 2.5 Flash; padrão `gemini-3.8-flash`. Com fallback ligado, indisponibilidade ou cota do modelo levam à tentativa das versões anteriores, sem repetir a mensagem na conversa. Erros de autenticação não acionam fallback. O chat transmite a resposta em trechos enquanto ela é gerada e oferece níveis de raciocínio rápido, equilibrado e profundo; o rápido é padrão.
- Modo noturno persistente em Ajustes.
- Ajustes confirmam a gravação criptografada da chave por leitura de volta. O campo fica vazio após sucesso porque a chave armazenada permanece oculta; falhas mantêm o texto digitado e exibem erro.

## Ainda precisa ser portado do OSONE atual

O projeto original inclui memória avançada, anexos, câmera/tela, casa inteligente, ferramentas e agente, COWORK, OSONE CODE, automações, contas e planos, voz clonada, handoff PC ↔ celular e outros módulos. Esses fluxos ainda não estão implementados. O app Live funciona com a voz nativa oferecida pelo modelo, sem clonagem de voz. Consulte `docs/PORTABILIDADE.md`.

## Abrir e compilar

1. Abra esta pasta no Android Studio com JDK 17 e Android SDK 36.
2. Faça a sincronização do Gradle e instale em um aparelho Android 8+ ou emulador.
3. Em Ajustes, cole a sua chave Gemini e toque em **Salvar chave**. O aviso “Chave salva e conferida” confirma a gravação; o campo vazio depois disso é proposital. Selecione o cérebro do chat e o fallback na mesma tela. O projeto não usa as credenciais privadas do repositório desktop.
4. Toque em 🎙️ Live e permita acesso ao microfone. Selecione o modelo no orbe ou em Ajustes; ative/desative o fallback conforme preferir. Ao sair ou bloquear o app, o microfone é encerrado.

Na CI, o workflow roda testes unitários, compila `assembleDebug` e disponibiliza o APK como artefato. A compilação local exige Android SDK e acesso às dependências do Google Maven/Maven Central. Este checkout não inclui `gradlew`; a CI instala Gradle, e o Android Studio pode gerar o wrapper para desenvolvimento local.

**Limites:** o modo Live exige internet, uma chave com acesso ao modelo escolhido, cota disponível, permissão do microfone e saída de áudio no aparelho. O chat escrito não importa automaticamente o contexto do Live, nem registra o áudio. A reconexão atual abre uma nova sessão, portanto não preserva o contexto da chamada de voz interrompida. O áudio usa a chave individual na conexão direta; para distribuição pública com credenciais de servidor, substituir por tokens efêmeros emitidos por backend. Ainda é necessário testar a latência, a conexão e o cancelamento acústico em aparelhos reais. O aplicativo não executa ações no celular em resposta ao texto do modelo.
