import { authorizeRequest, pluggyJson, sendError } from '../lib/pluggy.js'

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (!authorizeRequest(req)) return res.status(401).json({ message: 'Unauthorized' })

    const itemId = typeof req.body?.itemId === 'string' ? req.body.itemId.trim() : ''
    const clientUserId = process.env.PLUGGY_CLIENT_USER_ID?.trim() || 'assistente-financeiro'
    const payload = {
      options: {
        clientUserId,
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
