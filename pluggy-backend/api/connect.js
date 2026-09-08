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
<script>
const SDK_URLS=[
  'https://cdn.pluggy.ai/pluggy-connect/latest/pluggy-connect.js',
  'https://cdn.pluggy.ai/pluggy-connect/v2.7.0/pluggy-connect.js'
];
const ACCESS_KEY='assistfinanceiro.pluggy.accessCode';
const ITEM_KEY='assistfinanceiro.pluggy.updateItem';
const statusEl=document.getElementById('status');
const params=new URLSearchParams(location.search);
const hash=new URLSearchParams(location.hash.replace(/^#/,''));
const hashAccessCode=hash.get('accessCode')||'';
const queryItemId=params.get('itemId')||'';
function storageGet(key){try{return sessionStorage.getItem(key)||''}catch(_){return ''}}
function storageSet(key,value){try{if(value)sessionStorage.setItem(key,value);else sessionStorage.removeItem(key)}catch(_){}}
function clearSession(){storageSet(ACCESS_KEY,'');storageSet(ITEM_KEY,'')}
function fail(message,error){
  const item=error&&error.data&&error.data.item?error.data.item:null;
  const code=error&&(error.codeDescription||(error.data&&error.data.codeDescription))||'';
  const id=item&&item.id?item.id:'';
  const details=[message||'Não foi possível iniciar a conexão.',code&&('Código: '+code),id&&('Item: '+id)].filter(Boolean);
  statusEl.textContent=details.join(' — ');
}
function loadSdk(index=0){
  if(typeof window.PluggyConnect==='function')return Promise.resolve();
  if(index>=SDK_URLS.length)return Promise.reject(new Error('Não foi possível carregar o Pluggy Connect.'));
  return new Promise((resolve,reject)=>{
    const script=document.createElement('script');
    script.src=SDK_URLS[index];
    script.async=true;
    script.onload=()=>{
      if(typeof window.PluggyConnect==='function')resolve();
      else reject(new Error('SDK carregado sem expor PluggyConnect'));
    };
    script.onerror=()=>reject(new Error('Falha ao carregar '+SDK_URLS[index]));
    document.head.appendChild(script);
  }).catch(()=>loadSdk(index+1));
}
if(hashAccessCode){
  storageSet(ACCESS_KEY,hashAccessCode);
  history.replaceState(null,'',location.pathname+location.search);
}
if(queryItemId)storageSet(ITEM_KEY,queryItemId);
const accessCode=hashAccessCode||storageGet(ACCESS_KEY);
let itemId=queryItemId||storageGet(ITEM_KEY);
function openWidget(connectToken){
  if(!connectToken)return fail('Connect Token ausente.');
  if(typeof window.PluggyConnect!=='function')return fail('Pluggy Connect não foi carregado.');
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
  new window.PluggyConnect(config).init();
}
async function requestConnectToken(body,authorization){
  const headers={'Accept':'application/json','Content-Type':'application/json'};
  if(authorization)headers.Authorization='Bearer '+authorization;
  const response=await fetch('/api/connect-token',{
    method:'POST',
    headers,
    credentials:'same-origin',
    body:JSON.stringify(body||{})
  });
  const result=await response.json().catch(()=>({}));
  if(!response.ok)throw Object.assign(new Error(result.message||'Falha ao obter Connect Token'),result);
  return result;
}
async function start(){
  try{
    await loadSdk();
  }catch(error){
    fail(error.message,error);
    return;
  }

  const isOauthReturn=params.get('oauth')==='return';
  if(isOauthReturn){
    statusEl.textContent='Retomando autorização…';
    try{
      const resumed=await requestConnectToken({resume:true},'');
      if(resumed.itemId)itemId=resumed.itemId;
      openWidget(resumed.accessToken);
      return;
    }catch(error){
      if(!accessCode){
        fail(error.message||'Não foi possível retomar a autorização. Volte ao aplicativo e tente novamente.',error);
        return;
      }
    }
  }

  if(!accessCode){
    fail('Código de acesso ausente. Volte ao aplicativo e tente novamente.');
    return;
  }

  try{
    const result=await requestConnectToken(itemId?{itemId}:{},accessCode);
    openWidget(result.accessToken);
  }catch(error){
    fail(error.message,error);
  }
}
start();
</script></body></html>`)
}
