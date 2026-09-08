# Backend Pluggy Connect

Backend serverless mínimo para manter `PLUGGY_CLIENT_ID` e `PLUGGY_CLIENT_SECRET` fora do APK.

## Deploy no Vercel

1. Crie um projeto apontando para este repositório e use `pluggy-backend` como Root Directory.
2. Configure as variáveis de ambiente:
   - `PLUGGY_CLIENT_ID`
   - `PLUGGY_CLIENT_SECRET`
   - `APP_ACCESS_CODE` — código longo e aleatório usado apenas para parear este app de teste com o backend.
   - `PLUGGY_CLIENT_USER_ID` — opcional; para uso individual pode ser `assistente-financeiro`.
3. Faça o deploy.
4. No app de teste, informe uma única vez a URL HTTPS do projeto e o mesmo `APP_ACCESS_CODE`.

## Rotas

- `GET /api/connect` — abre o Pluggy Connect no navegador. O código de pareamento é enviado apenas no fragmento (`#accessCode=...`) e usado pelo JavaScript para solicitar um Connect Token.
- `POST /api/connect-token` — cria Connect Token com credenciais guardadas somente no servidor. O token inclui um `oauthRedirectUri` HTTPS apontando de volta para `/api/connect?oauth=return` e mantém `avoidDuplicates=true`. Ao criar o token, o backend também guarda temporariamente o próprio Connect Token em cookie `HttpOnly`, `Secure` e `SameSite=Lax` por até 30 minutos. No retorno OAuth, `POST /api/connect-token` com `{ "resume": true }` recupera a mesma sessão sem depender de `sessionStorage`, que pode se perder quando o navegador móvel retorna em outra aba/contexto.
- `GET /api/snapshot?itemId=...` — lê Item, contas, transações e faturas com uma API Key gerada no backend.

O aplicativo não recebe `CLIENT_ID`, `CLIENT_SECRET` nem `apiKey` da Pluggy.

## MeuPluggy

Para uso individual, as instituições reais são mantidas no MeuPluggy e compartilhadas com a aplicação de desenvolvimento pelo conector MeuPluggy. O fluxo móvel pode voltar da autorização OAuth em uma nova aba/contexto; por isso o backend configura `oauthRedirectUri` e preserva temporariamente o Connect Token em cookie seguro até que `onSuccess` devolva o `itemId` ao aplicativo.

Como `avoidDuplicates=true`, não mantenha simultaneamente um Item MeuPluggy criado apenas pela Demo do Dashboard e tente criar a mesma conexão novamente pelo app. Para validar a criação automática pelo app, remova o Item de demonstração correspondente antes da nova tentativa. Depois que o app capturar o `itemId`, as sincronizações seguintes reutilizam essa referência.

> Este backend é voltado ao fluxo individual/de teste atual. Antes de distribuição pública do app, substituir o `APP_ACCESS_CODE` compartilhado por autenticação de usuário e autorização por conexão.
