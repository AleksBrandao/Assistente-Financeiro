import {
  authorizeRequest,
  collectTransactionPages,
  pluggyJson,
  sendError,
} from '../lib/pluggy.js'

export default async function handler(req, res) {
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET')
    return res.status(405).json({ message: 'Method not allowed' })
  }

  try {
    if (!authorizeRequest(req)) return res.status(401).json({ message: 'Unauthorized' })
    const itemId = typeof req.query?.itemId === 'string' ? req.query.itemId.trim() : ''
    if (!itemId) return res.status(400).json({ message: 'itemId is required' })

    const item = await pluggyJson(`/items/${encodeURIComponent(itemId)}`)
    const accountsRoot = await pluggyJson(`/accounts?itemId=${encodeURIComponent(itemId)}`)
    const accounts = Array.isArray(accountsRoot.results) ? accountsRoot.results : []

    const datasets = await Promise.all(
      accounts.map(async (account) => {
        const accountId = account.id
        const transactions = await collectTransactionPages(accountId)
        let bills = []
        if (account.type === 'CREDIT') {
          const billsRoot = await pluggyJson(`/bills?accountId=${encodeURIComponent(accountId)}`)
          bills = Array.isArray(billsRoot.results) ? billsRoot.results : []
        }
        return { account, transactions, bills }
      }),
    )

    return res.status(200).json({ item, datasets })
  } catch (error) {
    return sendError(res, error)
  }
}
