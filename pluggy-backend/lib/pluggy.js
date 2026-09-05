const API_BASE = 'https://api.pluggy.ai'

let cachedApiKey = null
let cachedApiKeyExpiresAt = 0

export function authorizeRequest(req) {
  const expected = process.env.APP_ACCESS_CODE?.trim()
  if (!expected) {
    throw new Error('APP_ACCESS_CODE is not configured')
  }
  const provided = req.headers.authorization || ''
  return provided === `Bearer ${expected}`
}

export async function getApiKey() {
  const now = Date.now()
  if (cachedApiKey && now < cachedApiKeyExpiresAt) return cachedApiKey

  const clientId = process.env.PLUGGY_CLIENT_ID?.trim()
  const clientSecret = process.env.PLUGGY_CLIENT_SECRET?.trim()
  if (!clientId || !clientSecret) {
    throw new Error('PLUGGY_CLIENT_ID/PLUGGY_CLIENT_SECRET are not configured')
  }

  const response = await fetch(`${API_BASE}/auth`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ clientId, clientSecret }),
  })
  const body = await parseJson(response)
  if (!response.ok) throw pluggyError(response, body)

  const token = body.apiKey || body.accessToken
  if (!token) throw new Error('Pluggy authentication response did not contain an API key')
  cachedApiKey = token
  cachedApiKeyExpiresAt = now + 110 * 60 * 1000
  return token
}

export async function pluggyJson(path, init = {}) {
  if (!path.startsWith('/')) throw new Error('Only relative Pluggy API paths are allowed')
  const apiKey = await getApiKey()
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-API-KEY': apiKey,
      ...(init.headers || {}),
    },
  })
  const body = await parseJson(response)
  if (!response.ok) throw pluggyError(response, body)
  return body
}

export async function collectTransactionPages(accountId, maxPages = 100) {
  const endpoint = '/v2/transactions'
  let suffix = `?accountId=${encodeURIComponent(accountId)}`
  const results = []
  const seen = new Set()

  for (let page = 0; page < maxPages; page += 1) {
    const root = await pluggyJson(`${endpoint}${suffix}`)
    if (Array.isArray(root.results)) results.push(...root.results)
    const next = typeof root.next === 'string' && root.next ? root.next : null
    if (!next) return results
    if (!next.startsWith('?')) throw new Error('Invalid Pluggy transaction pagination cursor')
    if (seen.has(next)) return results
    seen.add(next)
    suffix = next
  }

  throw new Error(`Pluggy transaction pagination exceeded ${maxPages} pages`)
}

export function sendError(res, error) {
  const status = Number.isInteger(error?.status) ? error.status : 500
  const safeStatus = status >= 400 && status < 600 ? status : 500
  res.status(safeStatus).json({
    message: error?.message || 'Unexpected backend error',
    codeDescription: error?.codeDescription || null,
  })
}

async function parseJson(response) {
  const text = await response.text()
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

function pluggyError(response, body) {
  const error = new Error(body?.message || `Pluggy HTTP ${response.status}`)
  error.status = response.status
  error.codeDescription = body?.codeDescription || null
  return error
}
