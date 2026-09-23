# Atualizações do OSTIE

O Android instala uma versão por cima da anterior somente quando o `applicationId` continua `com.osone.app`, o `versionCode` aumenta e o novo APK é assinado por uma chave compatível com a instalada. Cada execução da CI até agora produziu um APK de depuração com uma chave temporária. O botão de atualização não consegue substituir esse certificado nem contornar a aprovação do instalador do Android.

## Configurar uma assinatura permanente

1. Se você possuir a chave que assinou o APK atualmente instalado, use **essa mesma chave**. Caso não tenha, a primeira migração para a assinatura permanente exige desinstalar o APK antigo uma única vez; anote suas chaves de API antes de desinstalar, pois o Android apagará os dados.
2. Crie e guarde uma chave em lugar seguro, fora do repositório. Por exemplo, num computador com JDK: `keytool -genkeypair -v -keystore ostie.jks -alias ostie -keyalg RSA -keysize 4096 -validity 10000`. Guarde backups do arquivo e da senha; perder a chave impede futuras atualizações deste pacote.
3. No GitHub, abra **OSONE-APP → Settings → Secrets and variables → Actions → New repository secret** e configure `OSTIE_SIGNING_KEYSTORE_BASE64` com o conteúdo base64 da chave (`base64 -w 0 ostie.jks` no Linux), `OSTIE_SIGNING_PASSWORD` com a senha da chave e `OSTIE_SIGNING_ALIAS` com o alias (por exemplo, `ostie`). A CI usa a mesma senha para o keystore e a chave; escolha a mesma nas duas perguntas do `keytool`. Nunca envie o arquivo `.jks` nem esses valores ao Git.
4. Execute o workflow **Android** no GitHub Actions. Baixe apenas o artefato **ostie-release-assinado** para instalar e atualizar. O artefato **ostie-debug-assinatura-temporaria** é apenas para desenvolvimento; a assinatura muda em cada execução. Guarde também um backup seguro do primeiro APK assinado para comparar o certificado em versões futuras.
5. A cada lançamento, aumente o `versionCode` em `app/build.gradle.kts`, sem mudar o `applicationId` nem a chave.

## Botão nas Configurações

Em **Configurações → Atualizações**, informe o endereço HTTPS de um `latest.json` acessível ao celular, toque em **Procurar atualização** e então em **Baixar e instalar atualização**. O app verifica o SHA-256 do download, identificador, versão e certificado e abre o instalador do Android para você confirmar. O Android pode pedir antes permissão para instalar apps desta origem. Também há **Escolher APK já baixado**: útil quando você baixou manualmente o artefato assinado e ainda não publicou um canal HTTPS.

Exemplo de `latest.json` hospedado junto a um APK acessível por HTTPS:

```json
{
  "versionCode": 14,
  "versionName": "0.14.0",
  "apkUrl": "https://SEU-DOMINIO/ostie-v0.14.0.apk",
  "sha256": "SUBSTITUA_PELOS_64_DIGITOS_HEXADECIMAIS_DO_SHA256_DO_APK",
  "notes": "Novidades desta versão"
}
```

Calcule o hash do arquivo final com `sha256sum ostie-v0.14.0.apk`. Atualize o JSON **depois** de disponibilizar o APK, usando o mesmo `versionCode` que está dentro dele. Um artefato de GitHub Actions em repositório privado exige autenticação e não é um link HTTPS público permanente: para verificação automática no celular é necessário configurar hospedagem HTTPS acessível ao aparelho. Não coloque tokens privados dentro do JSON, do link ou do APK. Sem hospedagem, use **Escolher APK já baixado**.

Se o certificado do APK antigo for diferente do novo, o app avisará antes de tentar instalar. Não há atualização por cima sem uma chave compatível; antes de migrar, preserve suas chaves de API para cadastrar novamente.
