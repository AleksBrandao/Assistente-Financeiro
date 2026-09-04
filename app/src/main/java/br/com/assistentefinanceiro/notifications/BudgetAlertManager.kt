package br.com.assistentefinanceiro.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import br.com.assistentefinanceiro.MainActivity
import br.com.assistentefinanceiro.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale

internal object BudgetAlertPolicy {
    private val levels = listOf(100, 110, 125, 150, 200)

    fun highestReachedLevel(limit: BigDecimal, projected: BigDecimal): Int? {
        if (limit.signum() <= 0) return null
        return levels.lastOrNull { percent ->
            val threshold = limit.multiply(BigDecimal(percent)).divide(BigDecimal(100))
            if (percent == 100) projected > threshold else projected >= threshold
        }
    }

    fun stateKey(period: YearMonth, categoryKey: String): String =
        "$period|$categoryKey|highest-level"

    fun legacyAlertKey(period: YearMonth, categoryKey: String): String = "$period|$categoryKey"

    fun notificationKey(period: YearMonth, categoryKey: String, level: Int): String =
        "$period|$categoryKey|$level"
}

class BudgetAlertManager(
    context: Context,
    private val store: DiagnosticStore = DiagnosticStore(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val preferences =
        appContext.getSharedPreferences(ALERT_PREFERENCES, Context.MODE_PRIVATE)

    init {
        ensureChannel(appContext)
    }

    fun evaluateLatestNotificationTransaction() {
        val latestEventId = store.recentEvents(1).firstOrNull()?.id ?: return
        val transaction = store.recentTransactions(MAX_TRANSACTION_SCAN)
            .firstOrNull { it.sourceEventId == latestEventId }
            ?: return
        evaluateTransaction(transaction.id)
    }

    fun evaluateTransaction(transactionId: Long) {
        val transactions = store.recentTransactions(MAX_TRANSACTION_SCAN)
        val transaction = transactions.firstOrNull { it.id == transactionId } ?: return
        if (transaction.direction != FinancialTransactionDirection.EXPENSE) return

        val period = MonthlyBudgetCalculator.periodFor(transaction) ?: return
        val matchingBudget = store.monthlyBudgets(period).firstOrNull { budget ->
            budget.categoryKey != null && matchesBudget(budget, transaction)
        } ?: return
        val categoryKey = matchingBudget.categoryKey ?: return

        val current = MonthlyBudgetCalculator.calculate(
            period = period,
            budgets = listOf(matchingBudget),
            transactions = transactions,
        ).single()
        val reachedLevel = BudgetAlertPolicy.highestReachedLevel(
            limit = matchingBudget.amount,
            projected = current.projected,
        ) ?: return

        val stateKey = BudgetAlertPolicy.stateKey(period, categoryKey)
        val legacyAlreadyAlerted = preferences.getBoolean(
            BudgetAlertPolicy.legacyAlertKey(period, categoryKey),
            false,
        )
        val lastAlertedLevel = preferences.getInt(
            stateKey,
            if (legacyAlreadyAlerted) 100 else 0,
        )
        if (reachedLevel <= lastAlertedLevel) return
        if (!canPostNotifications()) return

        val notificationKey = BudgetAlertPolicy.notificationKey(period, categoryKey, reachedLevel)
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_budget_alert)
            .setContentTitle(
                if (reachedLevel == 100) {
                    "Orçamento estourado: ${matchingBudget.displayName}"
                } else {
                    "Orçamento em $reachedLevel%: ${matchingBudget.displayName}"
                },
            )
            .setContentText(
                "Gasto projetado ${currency(current.projected)} de " +
                    "${currency(matchingBudget.amount)}. Faixa de $reachedLevel% atingida em $period.",
            )
            .setContentIntent(mainActivityPendingIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()

        notificationManager.notify(notificationKey.hashCode(), notification)
        preferences.edit().putInt(stateKey, reachedLevel).apply()
    }

    private fun matchesBudget(
        budget: MonthlyBudgetRecord,
        transaction: FinancialTransactionRecord,
    ): Boolean = if (budget.customCategory != null) {
        budget.customCategory == transaction.customCategory?.trim()?.takeIf(String::isNotEmpty)
    } else {
        transaction.customCategory.isNullOrBlank() && budget.category == transaction.category
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun currency(value: BigDecimal): String =
        NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)

    companion object {
        const val CHANNEL_ID = "budget_alerts"
        private const val CHANNEL_NAME = "Alertas de orçamento"
        private const val ALERT_PREFERENCES = "budget_alerts"
        private const val MAX_TRANSACTION_SCAN = 10_000

        fun ensureChannel(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Avisos locais ao atingir novas faixas do orçamento mensal"
                },
            )
        }
    }
}
