package br.com.assistentefinanceiro.data

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.FutureCardInstallmentProjection
import br.com.assistentefinanceiro.notifications.FutureCardInstallmentProjectionCalculator
import br.com.assistentefinanceiro.notifications.StatementCalculationEntry
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate

internal fun FinancialRepository.futureCardInstallmentProjections(
    asOfDate: LocalDate = LocalDate.now(),
): List<FutureCardInstallmentProjection> {
    val accounts = financialAccounts()
    val invoices = accounts.flatMap { account -> creditCardInvoices(account.id) }
    return FutureCardInstallmentProjectionCalculator.calculate(
        asOfDate = asOfDate,
        accounts = accounts,
        transactions = granularTransactions(),
        invoices = invoices,
    )
}

/**
 * Shared statement source for screens that must include both provider-backed invoices and inferred
 * remaining installments. The inferred rows are not persisted, so a later official Pluggy invoice
 * automatically replaces them without migration or cleanup.
 */
internal fun FinancialRepository.statementEntriesWithFutureInstallments(
    asOfDate: LocalDate = LocalDate.now(),
): List<StatementCalculationEntry> {
    val base = statementEntries()
    val future = futureCardInstallmentProjections(asOfDate)
        .mapIndexed { index, projection ->
            val amount = projection.amount.max(BigDecimal.ZERO)
            val transaction = FinancialTransactionRecord(
                id = Long.MIN_VALUE + index,
                sourceEventId = null,
                direction = FinancialTransactionDirection.EXPENSE,
                type = FinancialTransactionType.IMPORTED_EXPENSE,
                amount = amount.toPlainString(),
                occurredAt = projection.dueDate.atTime(23, 59, 59).toString(),
                description = "Parcelas futuras ${projection.accountName}",
                sourcePackage = "credit-card-installment-projection",
                status = TransactionStatus.PENDING,
                account = projection.accountName,
                accountId = projection.accountId,
                dueDate = projection.dueDate.toString(),
            )
            StatementCalculationEntry(
                transaction = transaction,
                realizedAmount = BigDecimal.ZERO,
                pendingAmount = amount,
            )
        }
    return base + future
}
