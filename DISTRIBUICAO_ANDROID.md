# Distribuição Android pelo GitHub

O APK publicado deve usar a mesma chave da instalação atual. Isso permite atualizar o
aplicativo sem remover o banco local.

## Configuração única

No PowerShell, confirme a chave usada pelo Android Studio/Gradle:

```powershell
$keystore = "$env:USERPROFILE\.android\debug.keystore"
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore $keystore -storepass android
```

O alias esperado é `androiddebugkey`. Não envie o arquivo ou suas senhas em comentários,
issues ou commits.

Converta a chave para Base64 e copie o conteúdo para a área de transferência:

```powershell
$keystore = "$env:USERPROFILE\.android\debug.keystore"
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
Set-Clipboard $base64
```

Em **Settings → Secrets and variables → Actions**, crie estes segredos:

| Segredo | Valor inicial esperado |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | conteúdo Base64 copiado |
| `ANDROID_KEYSTORE_PASSWORD` | `android` |
| `ANDROID_KEY_ALIAS` | `androiddebugkey` |
| `ANDROID_KEY_PASSWORD` | `android` |

Guarde também uma cópia offline do arquivo `debug.keystore`. Sem essa chave não será
possível assinar uma atualização compatível com a instalação atual.

## Publicar uma versão

Após alterar `versionCode` e `versionName`, faça o merge na `main`. No GitHub:

1. Abra **Actions**.
2. Selecione **Publicar APK Android**.
3. Clique em **Run workflow** e escolha `main`.
4. Aguarde a execução terminar.
5. Abra **Releases** e baixe o APK pelo celular.

O Android deverá apresentar a instalação como atualização, preservando os dados locais.
