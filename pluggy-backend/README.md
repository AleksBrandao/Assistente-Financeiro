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
- `POST /api/connect-token` — cria Connect Token com credenciais guardadas somente no servidor.
- `GET /api/snapshot?itemId=...` — lê Item, contas, transações e faturas com uma API Key gerada no backend.

O aplicativo não recebe `CLIENT_ID`, `CLIENT_SECRET` nem `apiKey` da Pluggy.

> Este backend é voltado ao fluxo individual/de teste atual. Antes de distribuição pública do app, substituir o `APP_ACCESS_CODE` compartilhado por autenticação de usuário e autorização por conexão.
