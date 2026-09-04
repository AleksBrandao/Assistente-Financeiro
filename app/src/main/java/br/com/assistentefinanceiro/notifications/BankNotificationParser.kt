package br.com.assistentefinanceiro.notifications

import java.time.LocalDateTime

data class BankNotification(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val body: String?,
    val receivedAt: LocalDateTime,
)

interface BankNotificationParser {
    fun canHandle(notification: BankNotification): Boolean

    fun parse(notification: BankNotification): ParsedFinancialTransaction?
}

object BankNotificationParserRegistry {
    private val parsers: List<BankNotificationParser> = listOf(
        SantanderBankNotificationParser,
        NubankNotificationParser,
        BradescoCardsNotificationParser,
    )

    fun parserFor(notification: BankNotification): BankNotificationParser? =
        parsers.firstOrNull { it.canHandle(notification) }

    fun supports(packageName: String, appLabel: String): Boolean =
        parserFor(
            BankNotification(
                packageName = packageName,
                appLabel = appLabel,
                title = null,
                body = null,
                receivedAt = LocalDateTime.MIN,
            )
        ) != null
}

object SantanderBankNotificationParser : BankNotificationParser {
    private const val SANTANDER_PACKAGE = "com.santander.app"

    override fun canHandle(notification: BankNotification): Boolean =
        notification.packageName == SANTANDER_PACKAGE ||
            notification.appLabel.contains("Santander", ignoreCase = true)

    override fun parse(notification: BankNotification): ParsedFinancialTransaction? {
        SantanderParser.parse(notification.title, notification.body)?.let { purchase ->
            return ParsedFinancialTransaction(
                type = FinancialTransactionType.CARD_PURCHASE,
                amount = purchase.amount,
                occurredAt = purchase.occurredAt,
                cardLastFour = purchase.cardLastFour,
                merchant = purchase.merchant,
            )
        }

        SantanderPixParser.parse(notification.title, notification.body)?.let { pix ->
            return ParsedFinancialTransaction(
                type = FinancialTransactionType.PIX_RECEIVED,
                amount = pix.amount,
                occurredAt = pix.occurredAt,
            )
        }

        return null
    }
}
