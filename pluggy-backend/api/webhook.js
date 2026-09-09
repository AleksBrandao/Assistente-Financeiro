import { recordWebhookEvent } from '../lib/webhook-state.js'

const SUPPORTED_EVENTS = new Set([
  'transactions/created',
  'transactions/updated',
  'transactions/deleted',
  'item/updated',
  'item/error',
])

function webhookAuthorized(req) {
  const expected = process.env.PLUGGY_WEBHOOK_SECRET?.trim()
  if (!expected) throw new Error('PLUGGY_WEBHOOK_SECRET is not configured')
  return (req.headers.authorization || '') === `Bearer ${expected}`
}

function payloadFrom(req) {
  if (req.body && typeof req.body === 'object') return req.body
  if (typeof req.body === 'string' && req.body.trim()) return JSON.parse(req.body)
  return {}
}

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (!webhookAuthorized(req)) return res.status(401).json({ message: 'Unauthorized' })

    const payload = payloadFrom(req)
    if (!SUPPORTED_EVENTS.has(payload.event)) {
      return res.status(400).json({ message: 'Unsupported webhook event' })
    }
    if (typeof payload.eventId !== 'string' || !payload.eventId.trim()) {
      return res.status(400).json({ message: 'eventId is required' })
    }
    if (typeof payload.itemId !== 'string' || !payload.itemId.trim()) {
      return res.status(400).json({ message: 'itemId is required' })
    }

    await recordWebhookEvent({
      ...payload,
      eventId: payload.eventId.trim(),
      itemId: payload.itemId.trim(),
    })

    // Pluggy treats every 2XX response as successful delivery.
    return res.status(204).end()
  } catch (error) {
    console.error('Pluggy webhook failed', error)
    return res.status(500).json({ message: error?.message || 'Webhook persistence failed' })
  }
}
