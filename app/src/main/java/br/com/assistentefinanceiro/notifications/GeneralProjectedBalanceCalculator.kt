package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate

data class ProjectedBalanceTransaction(
    val direction: FinancialTransactionDirection,
    val amount: BigDecimal,
    val occurredAt: LocalDate,
    val status: TransactionStatus,
    val dueDate: LocalDate? = null,
    val plannedPaymentDate: LocalDate? = null,
    val paidAt: LocalDate? = null,
) {
    val effectiveDate: LocalDate
        get() = if (status == TransactionStatus.REALIZED) {
            paidAt
        } else {
            plannedPaymentDate ?: dueDate
        } ?: occurredAt
}

object GeneralProjectedBalanceCalculator {
    fun calculate(
        throughDate: LocalDate,
        accounts: List<FinancialAccountRecord>,
        transactionsByAccount: Map<Long, List<ProjectedBalanceTransaction>>,
        movementsByAccount: Map<Long, List<AccountMovementRecord>>,
        invoices: List<CreditCardInvoiceRecord>,
        paymentsByInvoice: Map<Long, List<InvoicePaymentRecord>>,
    ): BigDecimal {
        val bankBalance = accounts
            .filter { account ->
                account.type == FinancialAccountType.BANK_ACCOUNT &&
                    (account.openingBalanceDate == null ||
                        !account.openingBalanceDate.isAfter(throughDate))
            }
            .fold(BigDecimal.ZERO) { total, account ->
                val fromDate = account.openingBalanceDate
                val transactions = transactionsByAccount[account.id]
                    .orEmpty()
                    .filter { transaction ->
                        (fromDate == null || transaction.effectiveDate.isAfter(fromDate)) &&
                            !transaction.effectiveDate.isAfter(throughDate)
                    }
                    .map { transaction ->
                        AccountBalanceEntry(
                            direction = transaction.direction,
                            amount = transaction.amount,
                            status = transaction.status,
                        )
                    }
                val movements = movementsByAccount[account.id]
                    .orEmpty()
                    .filter { movement ->
                        (fromDate == null || movement.occurredAt.isAfter(fromDate)) &&
                            !movement.occurredAt.isAfter(throughDate)
                    }
                total + AccountBalanceCalculator.calculate(
                    openingBalance = account.openingBalance,
                    transactions = transactions,
                    movements = movements,
                ).projectedBalance
            }

        val creditCardAccountIds = accounts
            .filter { it.type == FinancialAccountType.CREDIT_CARD }
            .mapTo(mutableSetOf()) { it.id }
        val invoiceAdjustment = invoices
            .filter { it.accountId in creditCardAccountIds }
            .fold(BigDecimal.ZERO) { total, invoice ->
                val payments = paymentsByInvoice[invoice.id]
                    .orEmpty()
                    .filter { !it.paidAt.isAfter(throughDate) }
                val paidThroughDate = payments.fold(BigDecimal.ZERO) { sum, payment ->
                    sum + payment.amount
                }
                val outstandingAtDate = (invoice.total - paidThroughDate)
                    .max(BigDecimal.ZERO)
                val dueOutstanding = if (
                    invoice.dueDate != null && !invoice.dueDate.isAfter(throughDate)
                ) {
                    outstandingAtDate
                } else {
                    BigDecimal.ZERO
                }
                val paymentsWithoutAccount = payments
                    .filter { it.sourceAccountId == null }
                    .fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }
                total + dueOutstanding + paymentsWithoutAccount
            }

        return bankBalance - invoiceAdjustment
    }
}
