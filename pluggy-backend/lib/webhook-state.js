import { get, put } from '@vercel/blob'

const MAX_RECENT_EVENT_IDS = 50

function statePath(itemId) {
  return `pluggy-webhooks/items/${encodeURIComponent(itemId)}/state.json`
}

function isNotFound(error) {
  return error?.name === 'BlobNotFoundError' ||
    error?.status === 404 ||
    error?.statusCode === 404 ||
    error?.code === 'not_found' ||
    error?.code === 'NOT_FOUND'
}

export async function readWebhookState(itemId) {
  try {
    const result = await get(statePath(itemId), {
      access: 'private',
      useCache: false,
    })
    if (!result?.stream) return null
    const text = await new Response(result.stream).text()
    return text ? JSON.parse(text) : null
  } catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

export async function recordWebhookEvent(payload) {
  const now = new Date().toISOString()
  const previous = await readWebhookState(payload.itemId)
  const recentEventIds = Array.isArray(previous?.recentEventIds) ? previous.recentEventIds : []

  // Pluggy reuses eventId when retrying the same notification. Keep the operation idempotent.
  if (recentEventIds.includes(payload.eventId)) return previous

  const transactionIds = Array.isArray(payload.transactionIds)
    ? payload.transactionIds.filter(value => typeof value === 'string').slice(0, 400)
    : []
  const transactionCount = Number.isInteger(payload.transactionsCount)
    ? payload.transactionsCount
    : transactionIds.length

  const counters = {
    created: Number(previous?.transactionChanges?.created || 0),
    updated: Number(previous?.transactionChanges?.updated || 0),
    deleted: Number(previous?.transactionChanges?.deleted || 0),
  }
  if (payload.event === 'transactions/created') counters.created += transactionCount
  if (payload.event === 'transactions/updated') counters.updated += transactionCount
  if (payload.event === 'transactions/deleted') counters.deleted += transactionCount

  const next = {
    itemId: payload.itemId,
    hasPendingChanges: true,
    firstPendingAt: previous?.hasPendingChanges ? previous.firstPendingAt : now,
    lastEventAt: now,
    lastEvent: payload.event,
    lastEventId: payload.eventId,
    lastTriggeredBy: typeof payload.triggeredBy === 'string' ? payload.triggeredBy : null,
    lastClientUserId: typeof payload.clientUserId === 'string' ? payload.clientUserId : null,
    lastAccountId: typeof payload.accountId === 'string' ? payload.accountId : null,
    lastTransactionIds: transactionIds,
    lastTransactionsCount: transactionCount,
    transactionChanges: counters,
    lastAcknowledgedAt: previous?.lastAcknowledgedAt || null,
    recentEventIds: [...recentEventIds, payload.eventId].slice(-MAX_RECENT_EVENT_IDS),
  }

  await put(statePath(payload.itemId), JSON.stringify(next), {
    access: 'private',
    contentType: 'application/json',
    addRandomSuffix: false,
    allowOverwrite: true,
  })
  return next
}

export async function acknowledgeWebhookState(itemId, throughEventId) {
  const current = await readWebhookState(itemId)
  if (!current) return null

  // Do not clear a newer event that arrived while the app was synchronizing.
  if (throughEventId && current.lastEventId !== throughEventId) return current

  const next = {
    ...current,
    hasPendingChanges: false,
    firstPendingAt: null,
    lastAcknowledgedAt: new Date().toISOString(),
    transactionChanges: { created: 0, updated: 0, deleted: 0 },
  }
  await put(statePath(itemId), JSON.stringify(next), {
    access: 'private',
    contentType: 'application/json',
    addRandomSuffix: false,
    allowOverwrite: true,
  })
  return next
}
