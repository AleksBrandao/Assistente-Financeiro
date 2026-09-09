import { authorizeRequest } from '../lib/pluggy.js'
import { registerPluggyWebhooks } from '../lib/webhook-registration.js'

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (!authorizeRequest(req)) return res.status(401).json({ message: 'Unauthorized' })
    const result = await registerPluggyWebhooks(req)
    return res.status(200).json(result)
  } catch (error) {
    console.error('Pluggy webhook registration failed', error)
    const status = Number.isInteger(error?.status) ? error.status : 500
    return res.status(status >= 400 && status < 600 ? status : 500).json({
      message: error?.message || 'Webhook registration failed',
      codeDescription: error?.codeDescription || null,
    })
  }
}
