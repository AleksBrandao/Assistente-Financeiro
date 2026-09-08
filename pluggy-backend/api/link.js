export default async function handler(req, res) {
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET')
    return res.status(405).send('Method not allowed')
  }

  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'none'; base-uri 'none'; form-action 'none'",
  )

  return res.status(200).send(`<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Vincular MeuPluggy</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; margin: 0; background: #f7f7f7; color: #171717; }
    main { max-width: 560px; margin: 0 auto; padding: 32px 20px; }
    .card { background: #fff; border: 1px solid #ddd; border-radius: 16px; padding: 20px; }
    h1 { margin-top: 0; font-size: 24px; }
    p { line-height: 1.5; }
    label { display: block; font-weight: 600; margin: 20px 0 8px; }
    input { box-sizing: border-box; width: 100%; padding: 14px; border: 1px solid #aaa; border-radius: 10px; font-size: 16px; }
    button { width: 100%; margin-top: 14px; padding: 14px; border: 0; border-radius: 10px; font-size: 16px; font-weight: 700; cursor: pointer; }
    button:disabled { cursor: not-allowed; opacity: .45; }
    .hint { font-size: 14px; color: #555; }
    .error { min-height: 24px; margin-top: 10px; color: #b00020; }
  </style>
</head>
<body>
<main>
  <div class="card">
    <h1>Vincular MeuPluggy</h1>
    <p>Use esta página somente depois de autorizar o MeuPluggy pela Demo oficial do Dashboard Pluggy.</p>
    <p class="hint">Cole abaixo o Item ID criado pela Demo. Ele não é enviado ao servidor: o navegador apenas abre o Assistente Financeiro, que salva o identificador localmente.</p>
    <label for="itemId">Item ID</label>
    <input id="itemId" type="text" inputmode="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
    <button id="linkButton" type="button" disabled>Vincular ao Assistente Financeiro</button>
    <div id="error" class="error" role="alert"></div>
  </div>
</main>
<script>
const input=document.getElementById('itemId');
const button=document.getElementById('linkButton');
const error=document.getElementById('error');
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function current(){return input.value.trim()}
function refresh(){
  const valid=uuid.test(current());
  button.disabled=!valid;
  error.textContent=current()&&!valid?'Item ID inválido. Copie novamente pelo Dashboard Pluggy.':'';
}
input.addEventListener('input',refresh);
button.addEventListener('click',()=>{
  const itemId=current();
  if(!uuid.test(itemId)){refresh();return;}
  location.href='assistfinanceiro://pluggy-connect?itemId='+encodeURIComponent(itemId);
});
refresh();
</script>
</body>
</html>`)
}
