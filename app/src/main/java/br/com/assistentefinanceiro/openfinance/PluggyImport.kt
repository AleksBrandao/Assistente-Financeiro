package br.com.assistentefinanceiro.openfinance

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class PluggyImportTransactionRequest(
    val externalId: String,
    val accountId: Long,
    val accountType: PluggyAccountType,
    val direction: PluggyTransactionDirection,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val description: String,
    val categoryRaw: String?,
    val billPeriod: YearMonth? = null,
    val billClosingDate: LocalDate? = null,
    val billDueDate: LocalDate? = null,
    val officialBillTotal: BigDecimal? = null,
) {
    init {
        require(externalId.isNotBlank())
        require(accountId > 0)
        require(amount.signum() != 0)
    }
}

data class PluggyImportResult(
    val imported: Int,
    val alreadyImported: Int,
    val rejected: Int,
)
