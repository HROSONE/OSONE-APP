# OSONE APP

Projeto **Android nativo** em Kotlin e Jetpack Compose, separado do [OSONE-AI-code](https://github.com/zerobob623-bit/OSONE-AI-code). Ainda não é uma cópia integral dos recursos do desktop.

## Funciona agora

- Conversa por texto via Gemini, OpenRouter ou Groq, com seleção de provedor e resposta em streaming. Gemini continua selecionado por padrão para quem já usava o app.
- Histórico local de até 100 mensagens e contexto das últimas 12 mensagens por chamada.
- Botão 🎙️ Live junto ao campo de texto abre um orbe central reativo ao volume de entrada e à resposta. A conversa usa PCM bidirecional via WebSocket Gemini Live, com interrupção da fala, sem transcrição e sem sintetizador do Android. O chat escrito continua acessível ao voltar.
- Seleção entre `gemini-3.8-live`, `gemini-3.1-flash-live-preview` e `gemini-2.5-flash-native-audio-preview-12-2025`, com fallback automático opcional. A configuração do Live usa `setup.generationConfig.responseModalities`; o app trata quadros WebSocket binários e de texto e exibe o código/categoria de falha de conexão sem revelar a chave.
- Escolha persistente entre 30 vozes predefinidas Gemini Live; trocar voz reinicia a sessão atual. A saída vai pelo canal de mídia do Android e o orbe oferece ganho de 50% a 200% (padrão 140%). Em volumes altos, o ganho digital pode saturar o áudio.
- Leitura opcional das respostas do **chat escrito** com TextToSpeech do Android.
- Três chaves separadas e criptografadas com Android Keystore e AES-GCM: Gemini para Live e opcionalmente chat, OpenRouter e Groq apenas para chat. A chave Gemini anterior continua disponível quando o Android instala a atualização sobre a versão existente, preservando os dados. Chaves não entram no Git nem no APK.
- Escolha do cérebro do chat entre Gemini 3.8, 3.7, 3.6, 3.5 e 2.5 Flash; padrão `gemini-3.8-flash`. Com fallback ligado, indisponibilidade ou cota do modelo levam à tentativa das versões anteriores, sem repetir a mensagem na conversa. Erros de autenticação não acionam fallback. O chat transmite a resposta em trechos enquanto ela é gerada e oferece níveis de raciocínio rápido, equilibrado e profundo; o rápido é padrão.
- No Groq, escolha entre Llama 3.3 70B e Llama 3.1 8B. No OpenRouter, o padrão `openrouter/free` seleciona automaticamente um modelo gratuito disponível; você também pode escrever outro ID de modelo, que pode ter custo. O fallback Gemini não troca de provedor nem usa outra chave automaticamente.
- Modo noturno persistente em Ajustes.
- Ajustes confirmam a gravação criptografada de cada chave por leitura de volta. O campo fica vazio após sucesso porque a chave armazenada permanece oculta; falhas mantêm o texto digitado e exibem erro.

## Ainda precisa ser portado do OSONE atual

O projeto original inclui memória avançada, anexos, câmera/tela, casa inteligente, ferramentas e agente, COWORK, OSONE CODE, automações, contas e planos, voz clonada, handoff PC ↔ celular e outros módulos. Esses fluxos ainda não estão implementados. O app Live funciona com a voz nativa oferecida pelo modelo, sem clonagem de voz. Consulte `docs/PORTABILIDADE.md`.

## Abrir e compilar

1. Abra esta pasta no Android Studio com JDK 17 e Android SDK 36.
2. Faça a sincronização do Gradle e instale em um aparelho Android 8+ ou emulador.
3. Em Ajustes, mantenha sua chave Gemini salva para usar o Live. Para o chat escrito, selecione Gemini, OpenRouter ou Groq e salve a chave correspondente. Cada campo informa se a chave está salva; depois de salvar, o campo vazio é proposital. O projeto não usa as credenciais privadas do repositório desktop.
4. Toque em 🎙️ Live e permita acesso ao microfone. Escolha modelo e voz no orbe ou em Ajustes; ajuste o volume no orbe e com os botões de mídia do celular. Ao sair ou bloquear o app, o microfone é encerrado.

Na CI, o workflow roda testes unitários, compila `assembleDebug` e disponibiliza o APK como artefato. A compilação local exige Android SDK e acesso às dependências do Google Maven/Maven Central. Este checkout não inclui `gradlew`; a CI instala Gradle, e o Android Studio pode gerar o wrapper para desenvolvimento local.

**Atenção ao instalar o APK de depuração:** a CI cria uma assinatura de teste nova a cada execução. O Android pode recusar a instalação por cima de um APK anterior. Desinstalar a versão antiga remove a chave Gemini, as demais chaves e o histórico do aparelho. Guarde suas chaves antes de desinstalar. Para atualizações futuras sem perda de dados, configurar uma chave de assinatura estável como segredo do repositório e publicar APKs assinados com ela.

**Limites:** o modo Live exige internet, uma chave Gemini com acesso ao modelo escolhido, cota disponível, permissão do microfone e saída de áudio no aparelho. A voz escolhida e a intensidade percebida podem variar entre modelos e aparelhos. O chat escrito não importa automaticamente o contexto do Live, nem registra o áudio. Reconectar ou trocar a voz abre outra sessão e perde o contexto de áudio anterior. As chaves individuais vão diretamente aos serviços escolhidos; para distribuição pública com credenciais de servidor, usar tokens efêmeros emitidos por backend. Ainda é necessário testar volume, latência e cancelamento acústico em diferentes aparelhos reais. O aplicativo não executa ações no celular em resposta ao texto do modelo.
