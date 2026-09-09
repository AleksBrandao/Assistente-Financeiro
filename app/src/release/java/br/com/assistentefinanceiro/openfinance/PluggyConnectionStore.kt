package br.com.assistentefinanceiro.openfinance

import android.content.Context
import java.util.UUID

internal data class PluggyConnectionSettings(
    val backendUrl: String,
    val accessCode: String,
    val itemId: String?,
)

internal class PluggyConnectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): PluggyConnectionSettings = PluggyConnectionSettings(
        backendUrl = preferences.getString(KEY_BACKEND_URL, "").orEmpty(),
        accessCode = preferences.getString(KEY_ACCESS_CODE, "").orEmpty(),
        itemId = preferences.getString(KEY_ITEM_ID, null)?.takeIf(String::isNotBlank),
    )

    fun saveBackend(backendUrl: String, accessCode: String) {
        val normalizedUrl = normalizeBackendUrl(backendUrl)
        require(accessCode.isNotBlank()) { "Código de acesso obrigatório" }
        preferences.edit()
            .putString(KEY_BACKEND_URL, normalizedUrl)
            .putString(KEY_ACCESS_CODE, accessCode.trim())
            .apply()
    }

    fun saveItemId(itemId: String) {
        runCatching { UUID.fromString(itemId.trim()) }
            .getOrElse { throw IllegalArgumentException("Item ID inválido") }
        preferences.edit().putString(KEY_ITEM_ID, itemId.trim()).apply()
    }

    fun clearConnection() {
        preferences.edit().remove(KEY_ITEM_ID).apply()
    }

    fun clearAll() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "pluggy_connection"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_ACCESS_CODE = "access_code"
        private const val KEY_ITEM_ID = "item_id"

        fun normalizeBackendUrl(value: String): String {
            val trimmed = value.trim().removeSuffix("/")
            require(trimmed.startsWith("https://")) { "Use uma URL HTTPS" }
            require(trimmed.length > "https://".length) { "URL do backend inválida" }
            return trimmed
        }
    }
}
