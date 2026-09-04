package br.com.assistentefinanceiro.openfinance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Domain models intentionally decoupled from Pluggy's JSON payloads.
 *
 * Raw credentials and sensitive counterparty data do not belong here. The transport layer should
 * sanitize card identifiers before creating these snapshots and should never persist/log Pluggy
 * clientSecret or apiKey values.
 */
enum class PluggyAccountType {
    BANK,
    CREDIT,
}

enum class PluggyTransactionStatus {
    PENDING,
    POSTED,
}

enum class PluggyTransactionDirection {
    CREDIT,
    DEBIT,
}

data class PluggyBankData(
    val closingBalance: BigDecimal? = null,
    val overdraftContractedLimit: BigDecimal? = null,
    val overdraftUsedLimit: BigDecimal? = null,
)

data class PluggyCreditData(
    val creditLimit: BigDecimal? = null,
    val availableCreditLimit: BigDecimal? = null,
    val balanceDueDate: LocalDate? = null,
    val minimumPayment: BigDecimal? = null,
)

data class PluggyAccountSnapshot(
    val externalId: String,
    val type: PluggyAccountType,
    val subtype: String?,
    val name: String,
    val currencyCode: String,
    val balance: BigDecimal,
    val bankData: PluggyBankData? = null,
    val creditData: PluggyCreditData? = null,
) {
    init {
        require(externalId.isNotBlank()) { "externalId must not be blank" }
    }
}

data class PluggyBillSnapshot(
    val externalId: String,
    val accountExternalId: String,
    val dueDate: LocalDate,
    val closingDate: LocalDate?,
    val totalAmount: BigDecimal,
    val minimumPaymentAmount: BigDecimal?,
    val allowsInstallments: Boolean?,
) {
    init {
        require(externalId.isNotBlank()) { "externalId must not be blank" }
        require(accountExternalId.isNotBlank()) { "accountExternalId must not be blank" }
    }
}

data class PluggyTransactionSnapshot(
    val externalId: String,
    val accountExternalId: String,
    val amount: BigDecimal,
    val date: Instant,
    val purchaseDate: Instant? = null,
    val direction: PluggyTransactionDirection,
    val status: PluggyTransactionStatus,
    val description: String,
    val category: String? = null,
    val categoryId: String? = null,
    val operationType: String? = null,
    val paymentMethod: String? = null,
    val installmentNumber: Int? = null,
    val totalInstallments: Int? = null,
    val totalAmount: BigDecimal? = null,
    val billForecastDate: YearMonth? = null,
    val billExternalId: String? = null,
    val cardLastFour: String? = null,
) {
    init {
        require(externalId.isNotBlank()) { "externalId must not be blank" }
        require(accountExternalId.isNotBlank()) { "accountExternalId must not be blank" }
        require(cardLastFour == null || (cardLastFour.length == 4 && cardLastFour.all(Char::isDigit))) {
            "cardLastFour must contain exactly four digits"
        }
        if (installmentNumber != null || totalInstallments != null) {
            require(installmentNumber != null && totalInstallments != null) {
                "installmentNumber and totalInstallments must be provided together"
            }
            require(installmentNumber > 0) { "installmentNumber must be positive" }
            require(totalInstallments > 0) { "totalInstallments must be positive" }
            require(installmentNumber <= totalInstallments) {
                "installmentNumber cannot exceed totalInstallments"
            }
        }
    }

    /** Original purchase instant when supplied by Pluggy; otherwise falls back to posting date. */
    val effectivePurchaseInstant: Instant
        get() = purchaseDate ?: date

    val isInstallment: Boolean
        get() = installmentNumber != null && totalInstallments != null

    /** Accounting amount without inferring direction from the sign. */
    val absoluteAmount: BigDecimal
        get() = amount.abs()
}

object PluggyDataSanitizer {
    /** Keeps only the last four digits of a raw/masked card number. */
    fun cardLastFour(raw: String?): String? = raw
        ?.filter(Char::isDigit)
        ?.takeLast(4)
        ?.takeIf { it.length == 4 }
}
