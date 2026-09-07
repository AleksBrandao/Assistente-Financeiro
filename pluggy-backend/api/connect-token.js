import { authorizeRequest, pluggyJson, sendError } from '../lib/pluggy.js'

function requestOrigin(req) {
  const forwardedHost = String(req.headers['x-forwarded-host'] || '').split(',')[0].trim()
  const host = forwardedHost || String(req.headers.host || '').trim()
  if (!host) throw new Error('Não foi possível determinar a URL pública do backend')

  const forwardedProto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim()
  const proto = forwardedProto || 'https'
  if (proto !== 'https') throw new Error('O retorno OAuth exige HTTPS')

  return `https://${host}`
}

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
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
    return res.status(200).json(token)
  } catch (error) {
    return sendError(res, error)
  }
}
