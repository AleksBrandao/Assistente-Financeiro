package br.com.assistentefinanceiro.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class FinanceNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(notification: StatusBarNotification) {
        val packageName = notification.packageName ?: return
        val applicationInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        val appLabel = applicationInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: packageName
        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val allowedPackages = BankPackagePreferences(applicationContext).allowedPackages()
        val supportedBank = BankNotificationParserRegistry.supports(packageName, appLabel)
        if (packageName !in allowedPackages && !supportedBank) return

        val store = DiagnosticStore(applicationContext)
        store.recordCandidate(packageName, appLabel, notification.postTime)

        if (packageName !in allowedPackages) return
        if (title.isBlank() && body.isBlank()) return
        NotificationReceivedAtContext.withPostedAt(notification.postTime) {
            store.recordEvent(
                packageName = packageName,
                appLabel = appLabel,
                title = title,
                body = body,
                postedAt = notification.postTime,
                notificationKey = notification.key,
            )
        }
        BudgetAlertManager(applicationContext, store).evaluateLatestNotificationTransaction()
    }
}
