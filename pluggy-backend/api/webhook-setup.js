export default function handler(req, res) {
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET')
    return res.status(405).send('Method not allowed')
  }

  res.setHeader('Cache-Control', 'no-store')
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'",
  )
  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  return res.status(200).send(`<!doctype html>
<html lang="pt-BR">
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Webhooks Pluggy</title>
<style>
body{font-family:system-ui,sans-serif;max-width:620px;margin:0 auto;padding:24px;background:#0d1512;color:#e6eee9}
.card{background:#15201c;border:1px solid #33463e;border-radius:18px;padding:20px;margin-top:20px}
input,button{width:100%;box-sizing:border-box;font:inherit;border-radius:12px;padding:14px;margin-top:10px}
input{background:#0d1512;color:#e6eee9;border:1px solid #5a7067}button{border:0;background:#8bd8c5;color:#102019;font-weight:700}
pre{white-space:pre-wrap;word-break:break-word;background:#0d1512;padding:12px;border-radius:10px;min-height:42px}
.small{color:#aebdb7;font-size:.92rem}
</style>
</head>
<body>
<h1>Webhooks Pluggy</h1>
<p>Registra os eventos de transações e Item no backend do Assistente Financeiro.</p>
<div class="card">
<label for="code">Código de pareamento do backend</label>
<input id="code" type="password" autocomplete="off" placeholder="APP_ACCESS_CODE">
<button id="register">Registrar webhooks</button>
<p class="small">O código é enviado somente no cabeçalho da requisição e não fica salvo nesta página.</p>
<pre id="result">Aguardando.</pre>
</div>
<script>
const button=document.getElementById('register');
const code=document.getElementById('code');
const result=document.getElementById('result');
button.addEventListener('click',async()=>{
  const value=code.value.trim();
  if(!value){result.textContent='Informe o código de pareamento.';return;}
  button.disabled=true;result.textContent='Registrando...';
  try{
    const response=await fetch('/api/webhook-register',{method:'POST',headers:{Authorization:'Bearer '+value,Accept:'application/json'}});
    const body=await response.json().catch(()=>({}));
    if(!response.ok) throw new Error(body.message||('HTTP '+response.status));
    result.textContent='Concluído.\n\n'+JSON.stringify(body,null,2);
    code.value='';
  }catch(error){result.textContent='Falha: '+error.message;}
  finally{button.disabled=false;}
});
</script>
</body>
</html>`)
}
