package br.com.assistentefinanceiro.data

import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.notifications.AccountBalanceSummary
import br.com.assistentefinanceiro.notifications.AccountMovementRecord
import br.com.assistentefinanceiro.notifications.BackupValidationResult
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceStatus
import br.com.assistentefinanceiro.notifications.DeletedTransactionGroup
import br.com.assistentefinanceiro.notifications.DiagnosticEvent
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.InvoicePaymentRecord
import br.com.assistentefinanceiro.notifications.MobillsImportResult
import br.com.assistentefinanceiro.notifications.MonthlyBudgetRecord
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionSeriesScope
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

interface FinancialRepository {
    fun candidates(): List<Pair<String, String>>
    fun recentEvents(limit: Int = 50): List<DiagnosticEvent>
    fun clearEvents()

    fun recentTransactions(limit: Int = 100): List<FinancialTransactionRecord>
    fun customCategories(direction: FinancialTransactionDirection): List<String>
    fun updateTransactionDetails(
        transactionId: Long,
        description: String,
        category: TransactionCategory,
        customCategory: String?,
        subcategory: String?,
        status: TransactionStatus,
        amount: BigDecimal,
        dueDate: LocalDate?,
        plannedPaymentDate: LocalDate?,
        paidAt: LocalDate?,
        applyToFuture: Boolean = false,
        seriesScope: TransactionSeriesScope = TransactionSeriesScope.ONLY_THIS,
    ): Boolean
    fun recordManualTransaction(
        accountId: Long,
        direction: FinancialTransactionDirection,
        amount: BigDecimal,
        occurredAt: LocalDate,
        description: String,
        status: TransactionStatus,
        occurrences: Int = 1,
    ): Boolean
    fun deleteManualTransaction(
        transactionId: Long,
        seriesScope: TransactionSeriesScope = TransactionSeriesScope.ONLY_THIS,
    ): Boolean
    fun invoiceTransactions(invoiceId: Long): List<FinancialTransactionRecord>

    fun financialAccounts(): List<FinancialAccountRecord>
    fun saveFinancialAccount(
        id: Long?,
        name: String,
        type: FinancialAccountType,
        closingDay: Int?,
        dueDay: Int?,
        isDefault: Boolean,
        cardIdentifiers: String?,
        openingBalance: BigDecimal,
        openingBalanceDate: LocalDate?,
    ): Boolean
    fun accountMovements(accountId: Long): List<AccountMovementRecord>
    fun accountBalance(
        account: FinancialAccountRecord,
        throughDate: LocalDate? = null,
    ): AccountBalanceSummary
    fun recordTransfer(
        sourceAccountId: Long,
        destinationAccountId: Long,
        amount: BigDecimal,
        occurredAt: LocalDate,
        description: String,
    ): Boolean
    fun deleteTransfer(movementId: Long): Boolean

    fun creditCardInvoices(accountId: Long): List<CreditCardInvoiceRecord>
    fun adjustInvoiceTotal(invoice: CreditCardInvoiceRecord, officialTotal: BigDecimal): Boolean
    fun recordInvoicePayment(
        invoice: CreditCardInvoiceRecord,
        amount: BigDecimal,
        paidAt: LocalDate,
        sourceAccountId: Long?,
    ): Boolean
    fun invoicePayments(invoice: CreditCardInvoiceRecord): List<InvoicePaymentRecord>
    fun deleteInvoicePayment(invoice: CreditCardInvoiceRecord, paymentId: Long): Boolean

    fun generalProjectedBalance(throughDate: LocalDate): BigDecimal
    fun generalProjectedBalances(throughDates: Collection<LocalDate>): Map<LocalDate, BigDecimal>

    fun monthlyBudgets(period: YearMonth): List<MonthlyBudgetRecord>
    fun saveMonthlyBudget(
        period: YearMonth,
        category: TransactionCategory?,
        amount: BigDecimal,
        customCategory: String? = null,
    ): Boolean
    fun deleteMonthlyBudget(
        period: YearMonth,
        category: TransactionCategory?,
        customCategory: String? = null,
    ): Boolean
    fun copyMonthlyBudgets(source: YearMonth, target: YearMonth): Int

    fun markExistingTransactions(preview: MobillsImportPreview): MobillsImportPreview
    fun importMobills(
        preview: MobillsImportPreview,
        includePossibleDuplicates: Boolean,
    ): MobillsImportResult

    fun externalAccountLinks(provider: ExternalDataProvider): List<ExternalAccountLinkRecord>
    fun saveExternalAccountLink(link: ExternalAccountLinkRecord): Boolean
    fun importExternalTransactions(
        drafts: List<ExternalTransactionImportDraft>,
    ): ExternalImportResult
    fun importExternalBills(
        drafts: List<ExternalBillImportDraft>,
    ): ExternalBillImportResult

    fun deletedTransactionGroups(): List<DeletedTransactionGroup>
    fun restoreDeletedTransactionGroup(groupId: String): Boolean
    fun permanentlyDeleteTransactionGroup(groupId: String): Boolean
    fun createBackupJson(): String
    fun previewBackup(content: String): BackupValidationResult
    fun restoreBackup(content: String): Boolean
    fun exportTransactionsCsv(): String

    fun granularTransactions(): List<FinancialTransactionRecord> = recentTransactions(10_000)

    fun consolidatedTransactions(): List<FinancialTransactionRecord> {
        val transactions = recentTransactions(10_000)
        val cards = financialAccounts().filter {
            it.type == FinancialAccountType.CREDIT_CARD
        }
        val cardIds = cards.map { it.id }.toSet()
        val invoices = cards.flatMap { account ->
            creditCardInvoices(account.id).mapNotNull { invoice ->
                val due = invoice.dueDate ?: return@mapNotNull null
                if (invoice.total.signum() == 0) return@mapNotNull null
                val paidAt = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                    invoicePayments(invoice).maxOfOrNull { it.paidAt }
                } else null
                FinancialTransactionRecord(
                    id = -invoice.id,
                    sourceEventId = null,
                    direction = if (invoice.total.signum() < 0) {
                        FinancialTransactionDirection.INCOME
                    } else FinancialTransactionDirection.EXPENSE,
                    type = if (invoice.total.signum() < 0) {
                        FinancialTransactionType.IMPORTED_INCOME
                    } else FinancialTransactionType.IMPORTED_EXPENSE,
                    amount = invoice.total.abs().toPlainString(),
                    occurredAt = due.atStartOfDay().toString(),
                    description = "Fatura ${account.name}",
                    sourcePackage = "credit-card-invoice",
                    status = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                        TransactionStatus.REALIZED
                    } else TransactionStatus.PENDING,
                    dueDate = due.toString(),
                    paidAt = paidAt?.toString(),
                )
            }
        }
        return transactions.filterNot { transaction ->
            transaction.type == FinancialTransactionType.CARD_PURCHASE ||
                (transaction.accountId != null && transaction.accountId in cardIds)
        } + invoices
    }
}
