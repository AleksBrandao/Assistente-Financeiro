import { authorizeRequest, pluggyJson } from '../lib/pluggy.js'

const EVENTS = [
  'transactions/created',
  'transactions/updated',
  'transactions/deleted',
  'item/updated',
  'item/error',
]

function webhookUrl(req) {
  const configured = process.env.PLUGGY_WEBHOOK_URL?.trim()
  if (configured) return configured

  const productionHost = process.env.VERCEL_PROJECT_PRODUCTION_URL?.trim()
  if (productionHost) return `https://${productionHost.replace(/^https?:\/\//, '')}/api/webhook`

  const forwardedProto = String(req.headers['x-forwarded-proto'] || 'https').split(',')[0].trim()
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim()
  if (!host) throw new Error('Could not determine public webhook URL')
  return `${forwardedProto}://${host}/api/webhook`
}

function existingWebhooks(root) {
  if (Array.isArray(root)) return root
  if (Array.isArray(root?.results)) return root.results
  if (Array.isArray(root?.webhooks)) return root.webhooks
  return []
}

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (!authorizeRequest(req)) return res.status(401).json({ message: 'Unauthorized' })

    const secret = process.env.PLUGGY_WEBHOOK_SECRET?.trim()
    if (!secret) throw new Error('PLUGGY_WEBHOOK_SECRET is not configured')

    const url = webhookUrl(req)
    if (!url.startsWith('https://')) throw new Error('Pluggy webhook URL must use HTTPS')

    const listed = await pluggyJson('/webhooks')
    const existing = existingWebhooks(listed)
    const created = []
    const alreadyPresent = []

    for (const event of EVENTS) {
      const found = existing.find(webhook => webhook?.event === event && webhook?.url === url)
      if (found) {
        alreadyPresent.push({ event, id: found.id || null })
        continue
      }

      const result = await pluggyJson('/webhooks', {
        method: 'POST',
        body: JSON.stringify({
          event,
          url,
          headers: {
            Authorization: `Bearer ${secret}`,
          },
        }),
      })
      created.push({ event, id: result?.id || null })
    }

    return res.status(200).json({
      webhookUrl: url,
      created,
      alreadyPresent,
      events: EVENTS,
    })
  } catch (error) {
    console.error('Pluggy webhook registration failed', error)
    const status = Number.isInteger(error?.status) ? error.status : 500
    return res.status(status >= 400 && status < 600 ? status : 500).json({
      message: error?.message || 'Webhook registration failed',
      codeDescription: error?.codeDescription || null,
    })
  }
}
