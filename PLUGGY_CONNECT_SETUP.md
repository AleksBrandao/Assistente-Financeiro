# Pluggy Connect — configuração do ambiente de teste

O fluxo do PR #39 elimina a digitação de `apiKey` e `Item ID` no Android.

## O que fica no backend

- `PLUGGY_CLIENT_ID`
- `PLUGGY_CLIENT_SECRET`
- geração de `apiKey`
- geração de Connect Token
- leitura de Item, contas, transações e Bills

Esses valores nunca devem ser colocados no APK.

## O que fica no aparelho

- URL HTTPS do backend Vercel;
- código de pareamento do ambiente individual/de teste;
- `itemId` retornado automaticamente pelo Pluggy Connect.

## Primeiro uso

1. Publicar a pasta `pluggy-backend` no Vercel.
2. Configurar no Vercel `PLUGGY_CLIENT_ID`, `PLUGGY_CLIENT_SECRET` e `APP_ACCESS_CODE`.
3. No app, abrir **Open Finance** e informar apenas a URL do backend e o mesmo código de pareamento.
4. Tocar em **Conectar instituição**.
5. Concluir o consentimento/login no Pluggy Connect.
6. O navegador retorna ao Assistente Financeiro e o `itemId` é salvo automaticamente.

## Próximas sincronizações

Basta abrir **Open Finance → Sincronizar Open Finance**. Não é necessário consultar Dashboard Pluggy nem copiar IDs/chaves.

## Distribuição pública futura

O código de pareamento compartilhado é adequado apenas ao uso individual/de teste atual. Antes de publicar o app para terceiros, adicionar autenticação real de usuário no backend e autorização por conexão.
