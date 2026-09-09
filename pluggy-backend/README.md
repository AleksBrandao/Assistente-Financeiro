# Backend Pluggy Connect

Backend serverless mínimo para manter `PLUGGY_CLIENT_ID` e `PLUGGY_CLIENT_SECRET` fora do APK.

## Deploy no Vercel

1. Crie um projeto apontando para este repositório e use `pluggy-backend` como Root Directory.
2. Configure as variáveis de ambiente:
   - `PLUGGY_CLIENT_ID`
   - `PLUGGY_CLIENT_SECRET`
   - `APP_ACCESS_CODE` — código longo e aleatório usado apenas para parear este app de teste com o backend.
   - `PLUGGY_CLIENT_USER_ID` — opcional; para uso individual pode ser `assistente-financeiro`.
   - `PLUGGY_WEBHOOK_SECRET` — segredo longo e aleatório usado exclusivamente para autenticar chamadas da Pluggy ao endpoint de webhook.
   - `PLUGGY_WEBHOOK_URL` — opcional; URL HTTPS completa do endpoint, por exemplo `https://seu-backend.vercel.app/api/webhook`. Se omitida, o backend usa a URL de produção fornecida pela Vercel.
3. Conecte ao projeto um **Vercel Blob privado**. O SDK `@vercel/blob` usa a credencial/OIDC fornecida pela Vercel e mantém o estado de webhook fora da memória efêmera das Functions.
4. Faça o deploy.
5. No app de teste, informe uma única vez a URL HTTPS do projeto e o mesmo `APP_ACCESS_CODE`.
6. Pelo celular, abra `GET /api/webhook-setup`, informe o `APP_ACCESS_CODE` e toque em **Registrar webhooks**.

## Rotas

- `GET /api/connect` — abre o Pluggy Connect no navegador. O código de pareamento é enviado apenas no fragmento (`#accessCode=...`) e usado pelo JavaScript para solicitar um Connect Token.
- `POST /api/connect-token` — cria Connect Token com credenciais guardadas somente no servidor. O token inclui um `oauthRedirectUri` HTTPS apontando de volta para `/api/connect?oauth=return` e mantém `avoidDuplicates=true`.
- `GET /api/link` — página de pareamento local para um Item já autorizado no Dashboard Pluggy.
- `GET /api/snapshot?itemId=...` — lê Item, contas, transações e faturas com uma API Key gerada no backend.
- `POST /api/webhook` — recebe notificações da Pluggy e grava um sinal durável por Item.
- `GET /api/webhook-status?itemId=...` — informa ao app se surgiram mudanças desde a última sincronização conhecida.
- `POST /api/webhook-status` — confirma um sinal após sincronização; recebe `itemId` e opcionalmente `throughEventId`. Se um evento mais novo tiver chegado durante a sincronização, ele não é apagado.
- `POST /api/webhook-register` — registra de forma idempotente os webhooks suportados na aplicação Pluggy.
- `GET /api/webhook-setup` — tela simples, voltada ao uso no celular, que envia o código de pareamento somente no cabeçalho e chama `/api/webhook-register`.

O aplicativo não recebe `CLIENT_ID`, `CLIENT_SECRET`, `apiKey`, `PLUGGY_WEBHOOK_SECRET` nem credenciais do Blob.

## Webhooks de dados

O registro atual assina os eventos oficiais:

- `transactions/created`
- `transactions/updated`
- `transactions/deleted`
- `item/updated`
- `item/error`

A Pluggy chama `/api/webhook` com `Authorization: Bearer <PLUGGY_WEBHOOK_SECRET>`. O backend valida o cabeçalho, usa `eventId` para tornar retries idempotentes e grava apenas metadados necessários para sinalizar atualização. O conteúdo financeiro continua sendo obtido pela rota `/api/snapshot`; o webhook não cria nem projeta transações.

O estado durável registra, entre outros campos, o último evento, horário, Item, conta envolvida, IDs de transações alteradas e contadores de `created/updated/deleted`. Isso permite evoluir o Android para consultar automaticamente se existem mudanças e então buscar um snapshot atualizado.

## MeuPluggy — fluxo estável para uso individual

O caminho oficialmente suportado pelo MeuPluggy para acesso pessoal é:

1. manter as instituições reais conectadas em `meu.pluggy.ai`;
2. habilitar o conector MeuPluggy na aplicação de desenvolvimento;
3. abrir a Demo da aplicação no Dashboard Pluggy e autorizar cada banco desejado;
4. copiar o Item ID criado pela Demo;
5. abrir `GET /api/link`, colar o Item ID uma única vez e tocar em **Vincular ao Assistente Financeiro**;
6. o aplicativo recebe o deep link `assistfinanceiro://pluggy-connect?itemId=...` e salva o Item ID localmente para as sincronizações seguintes.

Esse fluxo evita depender da retomada do OAuth do MeuPluggy em navegadores móveis. Depois do pareamento inicial, o Item ID não precisa mais ser digitado em cada sincronização.

Para uma distribuição multiusuário/publica, a evolução recomendada continua sendo persistir também a relação `clientUserId -> itemId` e substituir o `APP_ACCESS_CODE` compartilhado por autenticação de usuário e autorização por conexão.
