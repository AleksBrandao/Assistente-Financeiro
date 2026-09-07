export default async function handler(req, res) {
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET')
    return res.status(405).send('Method not allowed')
  }

  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  return res.status(200).send(`<!doctype html>
<html lang="pt-BR">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><title>Conectar Open Finance</title></head>
<body><main><h2>Assistente Financeiro</h2><p>Preparando a conexão segura com o Open Finance…</p><div id="status"></div></main>
<script src="https://cdn.pluggy.ai/pluggy-connect/v2.14.2/pluggy-connect.js"></script>
<script>
const ACCESS_KEY='assistfinanceiro.pluggy.accessCode';
const TOKEN_KEY='assistfinanceiro.pluggy.connectToken';
const ITEM_KEY='assistfinanceiro.pluggy.updateItem';
const statusEl=document.getElementById('status');
const params=new URLSearchParams(location.search);
const hash=new URLSearchParams(location.hash.replace(/^#/,''));
const hashAccessCode=hash.get('accessCode')||'';
const queryItemId=params.get('itemId')||'';
function storageGet(key){try{return sessionStorage.getItem(key)||''}catch(_){return ''}}
function storageSet(key,value){try{if(value)sessionStorage.setItem(key,value);else sessionStorage.removeItem(key)}catch(_){}}
function clearSession(){storageSet(TOKEN_KEY,'');storageSet(ITEM_KEY,'')}
function fail(message,error){
  const item=error&&error.data&&error.data.item?error.data.item:null;
  const code=error&&(error.codeDescription||(error.data&&error.data.codeDescription))||'';
  const id=item&&item.id?item.id:'';
  const details=[message||'Não foi possível iniciar a conexão.',code&&('Código: '+code),id&&('Item: '+id)].filter(Boolean);
  statusEl.textContent=details.join(' — ');
}
if(hashAccessCode){
  storageSet(ACCESS_KEY,hashAccessCode);
  history.replaceState(null,'',location.pathname+location.search);
}
if(queryItemId)storageSet(ITEM_KEY,queryItemId);
const accessCode=hashAccessCode||storageGet(ACCESS_KEY);
const itemId=queryItemId||storageGet(ITEM_KEY);
function openWidget(connectToken){
  if(!connectToken)return fail('Connect Token ausente.');
  storageSet(TOKEN_KEY,connectToken);
  const config={
    connectToken,
    includeSandbox:false,
    forceOauthInBrowser:true,
    onSuccess:data=>{
      const item=data&&data.item?data.item:data;
      const id=item&&item.id?item.id:'';
      if(!id)return fail('Conexão concluída, mas o Item ID não foi retornado.');
      clearSession();
      location.href='assistfinanceiro://pluggy-connect?itemId='+encodeURIComponent(id);
    },
    onError:error=>fail(error&&error.message?error.message:'Erro ao conectar instituição.',error),
    onClose:()=>{statusEl.textContent='Conexão fechada. Você pode voltar ao aplicativo.'}
  };
  if(itemId)config.updateItem=itemId;
  new PluggyConnect(config).init();
}
if(!accessCode){
  fail('Código de acesso ausente. Volte ao aplicativo e tente novamente.');
}else{
  const isOauthReturn=params.get('oauth')==='return';
  const storedToken=storageGet(TOKEN_KEY);
  if(isOauthReturn&&storedToken){
    statusEl.textContent='Retomando autorização…';
    openWidget(storedToken);
  }else{
    fetch('/api/connect-token',{
      method:'POST',
      headers:{'Accept':'application/json','Content-Type':'application/json','Authorization':'Bearer '+accessCode},
      body:JSON.stringify(itemId?{itemId}:{})
    }).then(async r=>{
      const body=await r.json().catch(()=>({}));
      if(!r.ok)throw Object.assign(new Error(body.message||'Falha ao obter Connect Token'),body);
      return body;
    }).then(({accessToken})=>openWidget(accessToken)).catch(error=>fail(error.message,error));
  }
}
</script></body></html>`)
}
