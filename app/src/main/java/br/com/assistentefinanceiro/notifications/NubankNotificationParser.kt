package br.com.assistentefinanceiro.notifications

object NubankNotificationParser : BankNotificationParser {
    private const val NUBANK_PACKAGE = "com.nu.production"

    private val purchasePattern = Regex(
        """^Compra\s+de\s+R\$\s*([\d.]+,\d{2})\s+APROVADA\s+em\s+(.+?)\s+para\s+o\s+cart[aã]o\s+com\s+final\s+(\d{4})\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    override fun canHandle(notification: BankNotification): Boolean =
        notification.packageName == NUBANK_PACKAGE

    override fun parse(notification: BankNotification): ParsedFinancialTransaction? {
        if (!notification.title.orEmpty().contains("Compra no crédito aprovada", ignoreCase = true)) {
            return null
        }

        val match = purchasePattern.matchEntire(notification.body.orEmpty().trim()) ?: return null
        val (rawAmount, rawMerchant, lastFour) = match.destructured
        val amount = runCatching {
            rawAmount.replace(".", "").replace(",", ".").toBigDecimal()
        }.getOrNull() ?: return null

        return ParsedFinancialTransaction(
            type = FinancialTransactionType.CARD_PURCHASE,
            amount = amount,
            occurredAt = notification.receivedAt,
            cardLastFour = lastFour,
            merchant = rawMerchant.trim(),
        )
    }
}
