import { authorizeRequest } from '../lib/pluggy.js'
import { acknowledgeWebhookState, readWebhookState } from '../lib/webhook-state.js'

function itemIdFrom(req) {
  if (typeof req.query?.itemId === 'string' && req.query.itemId.trim()) return req.query.itemId.trim()
  if (req.body && typeof req.body === 'object' && typeof req.body.itemId === 'string') {
    return req.body.itemId.trim()
  }
  return ''
}

export default async function handler(req, res) {
  if (!['GET', 'POST'].includes(req.method)) {
    res.setHeader('Allow', 'GET, POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (!authorizeRequest(req)) return res.status(401).json({ message: 'Unauthorized' })
    const itemId = itemIdFrom(req)
    if (!itemId) return res.status(400).json({ message: 'itemId is required' })

    res.setHeader('Cache-Control', 'no-store')

    if (req.method === 'GET') {
      const state = await readWebhookState(itemId)
      return res.status(200).json({
        itemId,
        hasPendingChanges: Boolean(state?.hasPendingChanges),
        state,
      })
    }

    const throughEventId = typeof req.body?.throughEventId === 'string'
      ? req.body.throughEventId.trim()
      : null
    const state = await acknowledgeWebhookState(itemId, throughEventId)
    return res.status(200).json({
      itemId,
      hasPendingChanges: Boolean(state?.hasPendingChanges),
      state,
    })
  } catch (error) {
    console.error('Pluggy webhook status failed', error)
    return res.status(500).json({ message: error?.message || 'Webhook status failed' })
  }
}
