package br.com.assistentefinanceiro.openfinance

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal data class PluggyWebhookTransactionChanges(
    val created: Int,
    val updated: Int,
    val deleted: Int,
)

internal data class PluggyWebhookStatus(
    val hasPendingChanges: Boolean,
    val lastEventId: String?,
    val lastEvent: String?,
    val lastEventAt: String?,
    val transactionChanges: PluggyWebhookTransactionChanges,
    val lastTransactionIds: List<String>,
)

/** Talks only to the Assistente Financeiro backend; Pluggy credentials stay on Vercel. */
internal class PluggyWebhookClient {
    fun fetchStatus(
        backendUrl: String,
        accessCode: String,
        itemId: String,
    ): PluggyWebhookStatus {
        val normalizedBackend = PluggyConnectionStore.normalizeBackendUrl(backendUrl)
        require(accessCode.isNotBlank()) { "Código de acesso obrigatório" }
        val encodedItemId = URLEncoder.encode(itemId.trim(), StandardCharsets.UTF_8.toString())
        val root = requestJson(
            url = "$normalizedBackend/api/webhook-status?itemId=$encodedItemId",
            accessCode = accessCode.trim(),
            method = "GET",
        )
        return parseStatus(root)
    }

    fun acknowledge(
        backendUrl: String,
        accessCode: String,
        itemId: String,
        throughEventId: String,
    ): PluggyWebhookStatus {
        val normalizedBackend = PluggyConnectionStore.normalizeBackendUrl(backendUrl)
        require(accessCode.isNotBlank()) { "Código de acesso obrigatório" }
        require(throughEventId.isNotBlank()) { "Evento de confirmação obrigatório" }
        val root = requestJson(
            url = "$normalizedBackend/api/webhook-status",
            accessCode = accessCode.trim(),
            method = "POST",
            body = JSONObject()
                .put("itemId", itemId.trim())
                .put("throughEventId", throughEventId.trim())
                .toString(),
        )
        return parseStatus(root)
    }

    private fun parseStatus(root: JSONObject): PluggyWebhookStatus {
        val state = root.optJSONObject("state")
        val changes = state?.optJSONObject("transactionChanges")
        val ids = state?.optJSONArray("lastTransactionIds")
        return PluggyWebhookStatus(
            hasPendingChanges = root.optBoolean("hasPendingChanges", false),
            lastEventId = state?.optString("lastEventId")?.takeIf(String::isNotBlank),
            lastEvent = state?.optString("lastEvent")?.takeIf(String::isNotBlank),
            lastEventAt = state?.optString("lastEventAt")?.takeIf(String::isNotBlank),
            transactionChanges = PluggyWebhookTransactionChanges(
                created = changes?.optInt("created", 0) ?: 0,
                updated = changes?.optInt("updated", 0) ?: 0,
                deleted = changes?.optInt("deleted", 0) ?: 0,
            ),
            lastTransactionIds = if (ids == null) {
                emptyList()
            } else {
                List(ids.length()) { index -> ids.optString(index) }
                    .filter(String::isNotBlank)
            },
        )
    }

    private fun requestJson(
        url: String,
        accessCode: String,
        method: String,
        body: String? = null,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessCode")
            instanceFollowRedirects = false
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(responseBody) }.getOrNull()
                throw PluggyApiException(
                    httpStatus = status,
                    codeDescription = error?.optString("codeDescription")?.takeIf(String::isNotBlank),
                    message = error?.optString("message")?.takeIf(String::isNotBlank)
                        ?: "Backend HTTP $status",
                )
            }
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
