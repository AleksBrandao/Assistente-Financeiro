package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDateTime

enum class NotificationClassification {
    TRANSACTION,
    IGNORED_PROMOTION,
    PENDING_RULE;

    companion object {
        fun fromStored(value: String?): NotificationClassification =
            entries.firstOrNull { it.name == value } ?: PENDING_RULE
    }
}

enum class FinancialTransactionDirection {
    INCOME,
    EXPENSE;

    companion object {
        fun fromStored(value: String?): FinancialTransactionDirection? =
            entries.firstOrNull { it.name == value }
    }
}

enum class FinancialTransactionType(
    val direction: FinancialTransactionDirection,
) {
    CARD_PURCHASE(FinancialTransactionDirection.EXPENSE),
    PIX_RECEIVED(FinancialTransactionDirection.INCOME),
    IMPORTED_EXPENSE(FinancialTransactionDirection.EXPENSE),
    IMPORTED_INCOME(FinancialTransactionDirection.INCOME),
    MANUAL_EXPENSE(FinancialTransactionDirection.EXPENSE),
    MANUAL_INCOME(FinancialTransactionDirection.INCOME);

    companion object {
        fun fromStored(value: String?): FinancialTransactionType? =
            entries.firstOrNull { it.name == value }
    }
}

data class ParsedFinancialTransaction(
    val type: FinancialTransactionType,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val cardLastFour: String? = null,
    val merchant: String? = null,
)

data class NotificationClassificationResult(
    val classification: NotificationClassification,
    val reason: String,
    val transaction: ParsedFinancialTransaction? = null,
)

object FinancialNotificationClassifier {
    private val creditAvailablePattern = Regex(
        """cr[eé]dito\s+dispon[ií]vel""",
        RegexOption.IGNORE_CASE,
    )
    private val profileOfferPattern = Regex(
        """confira\s+as\s+ofertas(?:\s+para\s+(?:o\s+)?seu\s+perfil)?""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(
        packageName: String,
        appLabel: String,
        title: String?,
        body: String?,
        receivedAt: LocalDateTime = NotificationReceivedAtContext.currentOrNow(),
    ): NotificationClassificationResult {
        val notification = BankNotification(
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            body = body,
            receivedAt = receivedAt,
        )
        val parser = BankNotificationParserRegistry.parserFor(notification)
            ?: return NotificationClassificationResult(
                classification = NotificationClassification.PENDING_RULE,
                reason = "Aplicativo ainda sem classificador",
            )

        parser.parse(notification)?.let { transaction ->
            return NotificationClassificationResult(
                classification = NotificationClassification.TRANSACTION,
                reason = when (transaction.type) {
                    FinancialTransactionType.CARD_PURCHASE -> "Compra aprovada reconhecida"
                    FinancialTransactionType.PIX_RECEIVED -> "PIX recebido reconhecido"
                    else -> "Transação reconhecida"
                },
                transaction = transaction,
            )
        }

        if (parser === SantanderBankNotificationParser) {
            val fullText = "${title.orEmpty()}\n${body.orEmpty()}"
            if (
                creditAvailablePattern.containsMatchIn(fullText) &&
                profileOfferPattern.containsMatchIn(fullText)
            ) {
                return NotificationClassificationResult(
                    classification = NotificationClassification.IGNORED_PROMOTION,
                    reason = "Oferta de crédito",
                )
            }
        }

        return NotificationClassificationResult(
            classification = NotificationClassification.PENDING_RULE,
            reason = "Nenhuma regra compatível",
        )
    }
}
