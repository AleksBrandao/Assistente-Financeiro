import { authorizeRequest } from '../lib/pluggy.js'
import { registerPluggyWebhooks } from '../lib/webhook-registration.js'

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function submittedCode(req) {
  if (req.body && typeof req.body === 'object' && !Buffer.isBuffer(req.body)) {
    return typeof req.body.code === 'string' ? req.body.code.trim() : ''
  }
  const raw = Buffer.isBuffer(req.body) ? req.body.toString('utf8') : String(req.body || '')
  return new URLSearchParams(raw).get('code')?.trim() || ''
}

function renderPage(res, { status = 200, outcome = null, error = null } = {}) {
  const result = outcome
    ? `Concluído.\n\n${JSON.stringify(outcome, null, 2)}`
    : error
      ? `Falha: ${error}`
      : 'Aguardando.'

  res.setHeader('Cache-Control', 'no-store')
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
  )
  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  return res.status(status).send(`<!doctype html>
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
<form method="post" action="/api/webhook-setup">
<label for="code">Código de pareamento do backend</label>
<input id="code" name="code" type="password" autocomplete="off" placeholder="APP_ACCESS_CODE" required>
<button type="submit">Registrar webhooks</button>
</form>
<p class="small">O código é enviado por HTTPS somente nesta solicitação e não é salvo pela página.</p>
<pre>${escapeHtml(result)}</pre>
</div>
</body>
</html>`)
}

export default async function handler(req, res) {
  if (req.method === 'GET') return renderPage(res)

  if (req.method !== 'POST') {
    res.setHeader('Allow', 'GET, POST')
    return res.status(405).send('Method not allowed')
  }

  const code = submittedCode(req)
  if (!code) return renderPage(res, { status: 400, error: 'Informe o código de pareamento.' })

  const authenticatedRequest = {
    ...req,
    headers: {
      ...req.headers,
      authorization: `Bearer ${code}`,
    },
  }

  try {
    if (!authorizeRequest(authenticatedRequest)) {
      return renderPage(res, { status: 401, error: 'Código de pareamento inválido.' })
    }
    const outcome = await registerPluggyWebhooks(req)
    return renderPage(res, { outcome })
  } catch (error) {
    console.error('Pluggy webhook setup failed', error)
    const status = Number.isInteger(error?.status) ? error.status : 500
    return renderPage(res, {
      status: status >= 400 && status < 600 ? status : 500,
      error: error?.message || 'Falha ao registrar webhooks.',
    })
  }
}
