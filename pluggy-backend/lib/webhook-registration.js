import { pluggyJson } from './pluggy.js'

export const PLUGGY_WEBHOOK_EVENTS = [
  'transactions/created',
  'transactions/updated',
  'transactions/deleted',
  'item/updated',
  'item/error',
]

function resolveWebhookUrl(req) {
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

export async function registerPluggyWebhooks(req) {
  const secret = process.env.PLUGGY_WEBHOOK_SECRET?.trim()
  if (!secret) throw new Error('PLUGGY_WEBHOOK_SECRET is not configured')

  const url = resolveWebhookUrl(req)
  if (!url.startsWith('https://')) throw new Error('Pluggy webhook URL must use HTTPS')

  const listed = await pluggyJson('/webhooks')
  const existing = existingWebhooks(listed)
  const created = []
  const alreadyPresent = []

  for (const event of PLUGGY_WEBHOOK_EVENTS) {
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

  return {
    webhookUrl: url,
    created,
    alreadyPresent,
    events: PLUGGY_WEBHOOK_EVENTS,
  }
}
