package br.com.assistentefinanceiro.data

import android.content.Context
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.notifications.AccountBalanceSummary
import br.com.assistentefinanceiro.notifications.AccountMovementRecord
import br.com.assistentefinanceiro.notifications.BackupValidationResult
import br.com.assistentefinanceiro.notifications.BudgetAlertManager
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.DeletedTransactionGroup
import br.com.assistentefinanceiro.notifications.DiagnosticEvent
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.InvoicePaymentRecord
import br.com.assistentefinanceiro.notifications.MobillsImportResult
import br.com.assistentefinanceiro.notifications.MonthlyBudgetRecord
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionSeriesScope
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

internal class DiagnosticFinancialRepository(
    context: Context,
) : FinancialRepository {
    private val appContext = context.applicationContext
    private val store = DiagnosticStore(appContext)
    private val budgetAlerts = BudgetAlertManager(appContext, store)

    override fun candidates(): List<Pair<String, String>> = store.candidates()
    override fun recentEvents(limit: Int): List<DiagnosticEvent> = store.recentEvents(limit)
    override fun clearEvents() = store.clearEvents()

    override fun recentTransactions(limit: Int): List<FinancialTransactionRecord> =
        store.recentTransactions(limit)

    override fun customCategories(direction: FinancialTransactionDirection): List<String> =
        store.customCategories(direction)

    override fun updateTransactionDetails(
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
        applyToFuture: Boolean,
        seriesScope: TransactionSeriesScope,
    ): Boolean {
        val updated = store.updateTransactionDetails(
            transactionId,
            description,
            category,
            customCategory,
            subcategory,
            status,
            amount,
            dueDate,
            plannedPaymentDate,
            paidAt,
            applyToFuture,
            seriesScope,
        )
        if (updated) {
            budgetAlerts.evaluateTransaction(
                transactionId = transactionId,
                allowBatchCrossing = applyToFuture || seriesScope != TransactionSeriesScope.ONLY_THIS,
            )
        }
        return updated
    }

    override fun recordManualTransaction(
        accountId: Long,
        direction: FinancialTransactionDirection,
        amount: BigDecimal,
        occurredAt: LocalDate,
        description: String,
        status: TransactionStatus,
        occurrences: Int,
    ): Boolean = store.recordManualTransaction(
        accountId, direction, amount, occurredAt, description, status, occurrences,
    )

    override fun deleteManualTransaction(
        transactionId: Long,
        seriesScope: TransactionSeriesScope,
    ): Boolean = store.deleteManualTransaction(transactionId, seriesScope)

    override fun invoiceTransactions(invoiceId: Long): List<FinancialTransactionRecord> =
        store.invoiceTransactions(invoiceId)

    override fun financialAccounts(): List<FinancialAccountRecord> = store.financialAccounts()

    override fun saveFinancialAccount(
        id: Long?,
        name: String,
        type: FinancialAccountType,
        closingDay: Int?,
        dueDay: Int?,
        isDefault: Boolean,
        cardIdentifiers: String?,
        openingBalance: BigDecimal,
        openingBalanceDate: LocalDate?,
    ): Boolean = store.saveFinancialAccount(
        id,
        name,
        type,
        closingDay,
        dueDay,
        isDefault,
        cardIdentifiers,
        openingBalance,
        openingBalanceDate,
    )

    override fun accountMovements(accountId: Long): List<AccountMovementRecord> =
        store.accountMovements(accountId)

    override fun accountBalance(
        account: FinancialAccountRecord,
        throughDate: LocalDate?,
    ): AccountBalanceSummary = store.accountBalance(account, throughDate)

    override fun recordTransfer(
        sourceAccountId: Long,
        destinationAccountId: Long,
        amount: BigDecimal,
        occurredAt: LocalDate,
        description: String,
    ): Boolean = store.recordTransfer(
        sourceAccountId, destinationAccountId, amount, occurredAt, description,
    )

    override fun deleteTransfer(movementId: Long): Boolean = store.deleteTransfer(movementId)

    override fun creditCardInvoices(accountId: Long): List<CreditCardInvoiceRecord> =
        store.creditCardInvoices(accountId)

    override fun adjustInvoiceTotal(
        invoice: CreditCardInvoiceRecord,
        officialTotal: BigDecimal,
    ): Boolean = store.adjustInvoiceTotal(invoice, officialTotal)

    override fun recordInvoicePayment(
        invoice: CreditCardInvoiceRecord,
        amount: BigDecimal,
        paidAt: LocalDate,
        sourceAccountId: Long?,
    ): Boolean = store.recordInvoicePayment(invoice, amount, paidAt, sourceAccountId)

    override fun invoicePayments(invoice: CreditCardInvoiceRecord): List<InvoicePaymentRecord> =
        store.invoicePayments(invoice)

    override fun deleteInvoicePayment(
        invoice: CreditCardInvoiceRecord,
        paymentId: Long,
    ): Boolean = store.deleteInvoicePayment(invoice, paymentId)

    override fun generalProjectedBalance(throughDate: LocalDate): BigDecimal =
        store.generalProjectedBalance(throughDate)

    override fun generalProjectedBalances(
        throughDates: Collection<LocalDate>,
    ): Map<LocalDate, BigDecimal> = store.generalProjectedBalances(throughDates)

    override fun monthlyBudgets(period: YearMonth): List<MonthlyBudgetRecord> =
        store.monthlyBudgets(period)

    override fun saveMonthlyBudget(
        period: YearMonth,
        category: TransactionCategory?,
        amount: BigDecimal,
        customCategory: String?,
    ): Boolean = store.saveMonthlyBudget(period, category, amount, customCategory)

    override fun deleteMonthlyBudget(
        period: YearMonth,
        category: TransactionCategory?,
        customCategory: String?,
    ): Boolean = store.deleteMonthlyBudget(period, category, customCategory)

    override fun copyMonthlyBudgets(source: YearMonth, target: YearMonth): Int =
        store.copyMonthlyBudgets(source, target)

    override fun markExistingTransactions(preview: MobillsImportPreview): MobillsImportPreview =
        store.markExistingTransactions(preview)

    override fun importMobills(
        preview: MobillsImportPreview,
        includePossibleDuplicates: Boolean,
    ): MobillsImportResult = store.importMobills(preview, includePossibleDuplicates)

    override fun deletedTransactionGroups(): List<DeletedTransactionGroup> =
        store.deletedTransactionGroups()

    override fun restoreDeletedTransactionGroup(groupId: String): Boolean =
        store.restoreDeletedTransactionGroup(groupId)

    override fun permanentlyDeleteTransactionGroup(groupId: String): Boolean =
        store.permanentlyDeleteTransactionGroup(groupId)

    override fun createBackupJson(): String = store.createBackupJson()
    override fun previewBackup(content: String): BackupValidationResult = store.previewBackup(content)
    override fun restoreBackup(content: String): Boolean = store.restoreBackup(content)
    override fun exportTransactionsCsv(): String = store.exportTransactionsCsv()
}
