# OSONE APP

Projeto **Android nativo** em Kotlin e Jetpack Compose, separado do [OSONE-AI-code](https://github.com/zerobob623-bit/OSONE-AI-code). Esta é a primeira versão funcional do aplicativo, não uma cópia integral dos recursos do desktop.

## Funciona agora

- Conversa por texto com Gemini via HTTPS, usando uma chave individual configurada no aparelho.
- Histórico local de até 100 mensagens e contexto das últimas 20 mensagens por chamada.
- Ditado via reconhecedor de fala disponível no Android e leitura da resposta por TextToSpeech, com botão para ativar/desativar.
- Chave criptografada com Android Keystore e AES-GCM; histórico em armazenamento privado do app. Chaves não entram no Git nem no APK.
- Escolha do modelo Gemini por nome; padrão `gemini-2.5-flash`.

## Ainda precisa ser portado do OSONE atual

O projeto original inclui Gemini Live com áudio contínuo, memória avançada, anexos, câmera/tela, casa inteligente, ferramentas e agente, COWORK, OSONE CODE, automações, contas e planos, voz clonada, handoff PC ↔ celular e outros módulos. Esses fluxos **não estão implementados** neste aplicativo inicial. Não faz sentido transplantar o executor de shell e as ações de mouse do desktop ao Android: precisam de ferramentas móveis próprias e permissões explícitas. Consulte `docs/PORTABILIDADE.md`.

## Abrir e compilar

1. Abra esta pasta no Android Studio com JDK 17 e Android SDK 36.
2. Faça a sincronização do Gradle e instale em um aparelho Android 8+ ou emulador.
3. Em Ajustes, salve a sua chave Gemini. O projeto não usa as credenciais privadas do repositório desktop.

Na CI, o workflow compila `assembleDebug` e disponibiliza o APK como artefato. A compilação local exige Android SDK e acesso às dependências do Google Maven/Maven Central. Este checkout não inclui `gradlew`; a CI instala Gradle, e o Android Studio pode gerar o wrapper para desenvolvimento local.

**Limites:** o ditado depende de um serviço de reconhecimento instalado no telefone; o Gemini depende de internet e de uma chave válida. O aplicativo não executa ações no celular em resposta ao texto do modelo.
