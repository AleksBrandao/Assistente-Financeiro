package br.com.assistentefinanceiro.openfinance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import br.com.assistentefinanceiro.MainActivity

internal class PluggyConnectCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent?.data?.getQueryParameter("itemId")
        if (!itemId.isNullOrBlank()) {
            runCatching { PluggyConnectionStore(applicationContext).saveItemId(itemId) }
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_PLUGGY_CONNECTED, !itemId.isNullOrBlank())
            },
        )
        finish()
    }

    companion object {
        const val EXTRA_PLUGGY_CONNECTED = "pluggy_connected"
    }
}
