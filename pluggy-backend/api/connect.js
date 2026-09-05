export default async function handler(req, res) {
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET')
    return res.status(405).send('Method not allowed')
  }

  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  return res.status(200).send(`<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Conectar Open Finance</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 0; background: #fafafa; color: #222; }
    main { max-width: 560px; margin: 0 auto; padding: 24px; }
    .card { background: white; border-radius: 16px; padding: 20px; box-shadow: 0 4px 18px rgba(0,0,0,.08); }
    .status { margin-top: 12px; color: #555; white-space: pre-wrap; }
  </style>
</head>
<body>
  <main>
    <div class="card">
      <h2>Assistente Financeiro</h2>
      <p>Preparando a conexão segura com o Open Finance…</p>
      <div id="status" class="status"></div>
    </div>
  </main>
  <script src="https://cdn.pluggy.ai/pluggy-connect/v2.8.2/pluggy-connect.js"></script>
  <script>
    const statusEl = document.getElementById('status')
    const params = new URLSearchParams(location.search)
    const hash = new URLSearchParams(location.hash.replace(/^#/, ''))
    const itemId = params.get('itemId') || ''
    const accessCode = hash.get('accessCode') || ''

    function fail(message) {
      statusEl.textContent = message || 'Não foi possível iniciar a conexão.'
    }

    if (!accessCode) {
      fail('Código de acesso ausente. Volte ao aplicativo e tente novamente.')
    } else {
      fetch('/api/connect-token', {
        method: 'POST',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + accessCode,
        },
        body: JSON.stringify(itemId ? { itemId } : {}),
      })
        .then(async response => {
          const body = await response.json().catch(() => ({}))
          if (!response.ok) throw new Error(body.message || 'Falha ao obter Connect Token')
          return body
        })
        .then(({ accessToken }) => {
          if (!accessToken) throw new Error('Connect Token ausente')
          const config = {
            connectToken: accessToken,
            includeSandbox: false,
            forceOauthInBrowser: true,
            onSuccess: data => {
              const item = data && data.item ? data.item : data
              const id = item && item.id ? item.id : ''
              if (!id) return fail('Conexão concluída, mas o Item ID não foi retornado.')
              location.href = 'assistfinanceiro://pluggy-connect?itemId=' + encodeURIComponent(id)
            },
            onError: error => fail(error && error.message ? error.message : 'Erro ao conectar instituição.'),
            onClose: () => { statusEl.textContent = 'Conexão fechada. Você pode voltar ao aplicativo.' },
          }
          if (itemId) config.updateItem = itemId
          const widget = new PluggyConnect(config)
          widget.init()
        })
        .catch(error => fail(error.message))
    }
  </script>
</body>
</html>`)
}
