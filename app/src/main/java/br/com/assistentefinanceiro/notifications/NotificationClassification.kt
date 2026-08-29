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
    PIX_RECEIVED(FinancialTransactionDirection.INCOME);

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
    private const val SANTANDER_PACKAGE = "com.santander.app"

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
    ): NotificationClassificationResult {
        if (!isSantander(packageName, appLabel)) {
            return NotificationClassificationResult(
                classification = NotificationClassification.PENDING_RULE,
                reason = "Aplicativo ainda sem classificador",
            )
        }

        SantanderParser.parse(title, body)?.let { purchase ->
            return NotificationClassificationResult(
                classification = NotificationClassification.TRANSACTION,
                reason = "Compra aprovada reconhecida",
                transaction = ParsedFinancialTransaction(
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = purchase.amount,
                    occurredAt = purchase.occurredAt,
                    cardLastFour = purchase.cardLastFour,
                    merchant = purchase.merchant,
                ),
            )
        }

        SantanderPixParser.parse(title, body)?.let { pix ->
            return NotificationClassificationResult(
                classification = NotificationClassification.TRANSACTION,
                reason = "PIX recebido reconhecido",
                transaction = ParsedFinancialTransaction(
                    type = FinancialTransactionType.PIX_RECEIVED,
                    amount = pix.amount,
                    occurredAt = pix.occurredAt,
                ),
            )
        }

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

        return NotificationClassificationResult(
            classification = NotificationClassification.PENDING_RULE,
            reason = "Nenhuma regra compatível",
        )
    }

    private fun isSantander(packageName: String, appLabel: String): Boolean =
        packageName == SANTANDER_PACKAGE || appLabel.contains("Santander", ignoreCase = true)
}
