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
- `GET /api/link` — página de pareamento local para um Item já autorizado no Dashboard Pluggy. O Item ID é validado no navegador e enviado somente ao deep link do aplicativo; ele não é submetido ao backend.
- `GET /api/snapshot?itemId=...` — lê Item, contas, transações e faturas com uma API Key gerada no backend.

O aplicativo não recebe `CLIENT_ID`, `CLIENT_SECRET` nem `apiKey` da Pluggy.

## MeuPluggy — fluxo estável para uso individual

O caminho oficialmente suportado pelo MeuPluggy para acesso pessoal é:

1. manter as instituições reais conectadas em `meu.pluggy.ai`;
2. habilitar o conector MeuPluggy na aplicação de desenvolvimento;
3. abrir a Demo da aplicação no Dashboard Pluggy e autorizar cada banco desejado;
4. copiar o Item ID criado pela Demo;
5. abrir `GET /api/link`, colar o Item ID uma única vez e tocar em **Vincular ao Assistente Financeiro**;
6. o aplicativo recebe o deep link `assistfinanceiro://pluggy-connect?itemId=...` e salva o Item ID localmente para as sincronizações seguintes.

Esse fluxo evita depender da retomada do OAuth do MeuPluggy em navegadores móveis, que pode mudar de aba/contexto. Depois do pareamento inicial, o Item ID não precisa mais ser digitado ou recuperado em cada sincronização.

Para uma distribuição multiusuário/publica, a evolução recomendada é registrar `item/created` por webhook e persistir a relação `clientUserId -> itemId` em armazenamento durável no backend. Isso elimina inclusive o pareamento manual inicial.

> Este backend é voltado ao fluxo individual/de teste atual. Antes de distribuição pública do app, substituir o `APP_ACCESS_CODE` compartilhado por autenticação de usuário e autorização por conexão.
