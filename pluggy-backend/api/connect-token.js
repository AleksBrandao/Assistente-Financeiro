import { authorizeRequest, pluggyJson, sendError } from '../lib/pluggy.js'

const TOKEN_COOKIE = 'assistfinanceiro_pluggy_connect_token'
const ITEM_COOKIE = 'assistfinanceiro_pluggy_update_item'
const COOKIE_MAX_AGE_SECONDS = 30 * 60

function requestOrigin(req) {
  const forwardedHost = String(req.headers['x-forwarded-host'] || '').split(',')[0].trim()
  const host = forwardedHost || String(req.headers.host || '').trim()
  if (!host) throw new Error('Não foi possível determinar a URL pública do backend')

  const forwardedProto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim()
  const proto = forwardedProto || 'https'
  if (proto !== 'https') throw new Error('O retorno OAuth exige HTTPS')

  return `https://${host}`
}

function parseCookies(req) {
  const header = String(req.headers.cookie || '')
  return header.split(';').reduce((result, pair) => {
    const separator = pair.indexOf('=')
    if (separator <= 0) return result
    const key = pair.slice(0, separator).trim()
    const rawValue = pair.slice(separator + 1).trim()
    try {
      result[key] = decodeURIComponent(rawValue)
    } catch (_) {
      result[key] = rawValue
    }
    return result
  }, {})
}

function secureCookie(name, value, maxAge = COOKIE_MAX_AGE_SECONDS) {
  return `${name}=${encodeURIComponent(value)}; Max-Age=${maxAge}; Path=/; HttpOnly; Secure; SameSite=Lax`
}

function saveResumeState(res, accessToken, itemId) {
  const cookies = [secureCookie(TOKEN_COOKIE, accessToken)]
  if (itemId) cookies.push(secureCookie(ITEM_COOKIE, itemId))
  else cookies.push(secureCookie(ITEM_COOKIE, '', 0))
  res.setHeader('Set-Cookie', cookies)
}

function resumeFromCookie(req, res) {
  const cookies = parseCookies(req)
  const accessToken = String(cookies[TOKEN_COOKIE] || '').trim()
  if (!accessToken) {
    return res.status(401).json({
      message: 'Sessão OAuth não encontrada. Volte ao aplicativo e inicie a conexão novamente.',
    })
  }
  const itemId = String(cookies[ITEM_COOKIE] || '').trim()
  return res.status(200).json({ accessToken, itemId })
}

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (req.body?.resume === true) return resumeFromCookie(req, res)

    if (!authorizeRequest(req)) return res.status(401).json({ message: 'Unauthorized' })

    const itemId = typeof req.body?.itemId === 'string' ? req.body.itemId.trim() : ''
    const clientUserId = process.env.PLUGGY_CLIENT_USER_ID?.trim() || 'assistente-financeiro'
    const oauthRedirectUri = `${requestOrigin(req)}/api/connect?oauth=return`
    const payload = {
      options: {
        clientUserId,
        oauthRedirectUri,
        avoidDuplicates: true,
      },
    }
    if (itemId) payload.itemId = itemId

    const token = await pluggyJson('/connect_token', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
    const accessToken = String(token?.accessToken || '').trim()
    if (!accessToken) throw new Error('A Pluggy não retornou um Connect Token válido')

    saveResumeState(res, accessToken, itemId)
    return res.status(200).json(token)
  } catch (error) {
    return sendError(res, error)
  }
}
