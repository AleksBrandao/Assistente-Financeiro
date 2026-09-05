package br.com.assistentefinanceiro.data

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

enum class ExternalDataProvider {
    PLUGGY,
}

data class ExternalAccountLinkRecord(
    val provider: ExternalDataProvider,
    val externalAccountId: String,
    val localAccountId: Long,
)

data class ExternalTransactionImportDraft(
    val provider: ExternalDataProvider,
    val externalTransactionId: String,
    val externalAccountId: String,
    val localAccountId: Long,
    val direction: FinancialTransactionDirection,
    val type: FinancialTransactionType,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val description: String,
    val status: TransactionStatus,
    val category: TransactionCategory = TransactionCategory.UNCATEGORIZED,
    val customCategory: String? = null,
    val originalCategory: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.PLUGGY,
    val purchaseAt: String? = null,
    val sourceCategoryId: String? = null,
    val operationType: String? = null,
    val paymentMethod: String? = null,
    val installmentNumber: Int? = null,
    val totalInstallments: Int? = null,
    val billForecastPeriod: YearMonth? = null,
    val externalBillId: String? = null,
    val invoiceClosingDate: LocalDate? = null,
    val invoiceDueDate: LocalDate? = null,
) {
    init {
        require(externalTransactionId.isNotBlank())
        require(externalAccountId.isNotBlank())
        require(localAccountId > 0)
        require(type.direction == direction) { "type and direction must be consistent" }
        require(amount.signum() > 0)
        require(description.isNotBlank())
        if (installmentNumber != null || totalInstallments != null) {
            require(installmentNumber != null && totalInstallments != null)
            require(installmentNumber in 1..totalInstallments)
        }
    }
}

data class ExternalBillPaymentDraft(
    val externalPaymentId: String,
    val amount: BigDecimal,
    val paidAt: LocalDate,
) {
    init {
        require(externalPaymentId.isNotBlank())
        require(amount.signum() > 0)
    }
}

data class ExternalBillImportDraft(
    val provider: ExternalDataProvider,
    val externalBillId: String,
    val externalAccountId: String,
    val localAccountId: Long,
    val dueDate: LocalDate,
    val closingDate: LocalDate?,
    val totalAmount: BigDecimal,
    val payments: List<ExternalBillPaymentDraft> = emptyList(),
    val financeChargeTotal: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(externalBillId.isNotBlank())
        require(externalAccountId.isNotBlank())
        require(localAccountId > 0)
        require(totalAmount.signum() >= 0)
        require(financeChargeTotal.signum() >= 0)
    }
}

data class ExternalImportResult(
    val imported: Int,
    val alreadyImported: Int,
    val updated: Int = 0,
)

data class ExternalBillImportResult(
    val billsSynced: Int,
    val paymentsSynced: Int,
)
