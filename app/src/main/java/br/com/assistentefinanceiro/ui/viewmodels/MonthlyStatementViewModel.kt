package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.assistentefinanceiro.importing.MobillsImportAnalyzer
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.importing.SimpleXlsxReader
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceStatus
import br.com.assistentefinanceiro.notifications.DailyTransactionGroup
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.MonthlyStatement
import br.com.assistentefinanceiro.notifications.MonthlyStatementCalculator
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionSeriesScope
import br.com.assistentefinanceiro.notifications.TransactionStatus
import br.com.assistentefinanceiro.ui.screens.LoadState
import br.com.assistentefinanceiro.ui.screens.StatementInvoiceItem
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MonthlyStatementUiState(
    val selectedMonth: YearMonth,
    val pendingOnly: Boolean = false,
    val withoutCategoryOnly: Boolean = false,
    val withoutSubcategoryOnly: Boolean = false,
    val statement: MonthlyStatement,
    val visibleGroups: List<DailyTransactionGroup>,
    val invoiceItemByTransactionId: Map<Long, StatementInvoiceItem>,
    val unconsolidatedCardTransactionCount: Int,
    val generalProjectedBalance: LoadState<BigDecimal> = LoadState.Loading,
    val editingTransaction: FinancialTransactionRecord? = null,
    val deletingTransaction: FinancialTransactionRecord? = null,
    val deletingScope: TransactionSeriesScope = TransactionSeriesScope.ONLY_THIS,
    val importPreview: MobillsImportPreview? = null,
    val includePossibleDuplicates: Boolean = false,
    val importMessage: String? = null,
    val importError: String? = null,
    val readingImport: Boolean = false,
    val selectedInvoice: StatementInvoiceItem? = null,
    val customCategories: Map<FinancialTransactionDirection, List<String>> = emptyMap(),
) {
    val filtersActive: Boolean
        get() = pendingOnly || withoutCategoryOnly || withoutSubcategoryOnly
}

internal class MonthlyStatementViewModel(
    private val store: DiagnosticStore,
) : ViewModel() {
    private var transactions: List<FinancialTransactionRecord> = emptyList()
    private var invoiceItems: List<StatementInvoiceItem> = emptyList()
    private var creditCardAccountIds: Set<Long> = emptySet()
    private var balanceJob: Job? = null

    private val _uiState = MutableStateFlow(loadState(YearMonth.now()))
    val uiState: StateFlow<MonthlyStatementUiState> = _uiState.asStateFlow()

    init {
        loadProjectedBalance()
    }

    fun refresh() {
        val current = _uiState.value
        _uiState.value = loadState(current.selectedMonth).copy(
            pendingOnly = current.pendingOnly,
            withoutCategoryOnly = current.withoutCategoryOnly,
            withoutSubcategoryOnly = current.withoutSubcategoryOnly,
        ).withVisibleGroups()
        loadProjectedBalance()
    }

    fun showPreviousMonth() = selectMonth(_uiState.value.selectedMonth.minusMonths(1))

    fun showNextMonth() = selectMonth(_uiState.value.selectedMonth.plusMonths(1))

    fun togglePendingOnly() = updateFilters { copy(pendingOnly = !pendingOnly) }

    fun toggleWithoutCategoryOnly() =
        updateFilters { copy(withoutCategoryOnly = !withoutCategoryOnly) }

    fun toggleWithoutSubcategoryOnly() =
        updateFilters { copy(withoutSubcategoryOnly = !withoutSubcategoryOnly) }

    fun clearFilters() = updateFilters {
        copy(
            pendingOnly = false,
            withoutCategoryOnly = false,
            withoutSubcategoryOnly = false,
        )
    }

    fun editTransaction(transaction: FinancialTransactionRecord?) {
        _uiState.value = _uiState.value.copy(editingTransaction = transaction)
    }

    fun requestDelete(transaction: FinancialTransactionRecord, scope: TransactionSeriesScope) {
        _uiState.value = _uiState.value.copy(
            editingTransaction = null,
            deletingTransaction = transaction,
            deletingScope = scope,
        )
    }

    fun dismissDelete() {
        _uiState.value = _uiState.value.copy(deletingTransaction = null)
    }

    fun confirmDelete() {
        val state = _uiState.value
        val transaction = state.deletingTransaction ?: return
        if (store.deleteManualTransaction(transaction.id, state.deletingScope)) refresh()
    }

    fun saveTransaction(
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
    ) {
        val transaction = _uiState.value.editingTransaction ?: return
        if (
            store.updateTransactionDetails(
                transaction.id,
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
        ) refresh()
    }

    fun importMobills(openInputStream: () -> InputStream?) {
        _uiState.value = _uiState.value.copy(readingImport = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    openInputStream().use { input ->
                        requireNotNull(input) { "Não foi possível abrir o arquivo" }
                        store.markExistingTransactions(
                            MobillsImportAnalyzer.analyze(
                                rawRows = SimpleXlsxReader.readMobillsRows(input),
                            ),
                        )
                    }
                }
            }.onSuccess { preview ->
                _uiState.value = _uiState.value.copy(
                    importPreview = preview,
                    includePossibleDuplicates = false,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(
                    importError = error.message ?: "Arquivo Mobills inválido",
                )
            }
            _uiState.value = _uiState.value.copy(readingImport = false)
        }
    }

    fun setIncludePossibleDuplicates(include: Boolean) {
        _uiState.value = _uiState.value.copy(includePossibleDuplicates = include)
    }

    fun dismissImportPreview() {
        _uiState.value = _uiState.value.copy(importPreview = null)
    }

    fun confirmImport() {
        val state = _uiState.value
        val preview = state.importPreview ?: return
        val result = store.importMobills(preview, state.includePossibleDuplicates)
        val message = "${result.imported} movimentações importadas" +
            if (result.alreadyImported > 0) {
                " · ${result.alreadyImported} já existentes"
            } else ""
        refresh()
        _uiState.value = _uiState.value.copy(importMessage = message)
    }

    fun dismissImportMessage() {
        _uiState.value = _uiState.value.copy(importMessage = null)
    }

    fun dismissImportError() {
        _uiState.value = _uiState.value.copy(importError = null)
    }

    fun selectInvoice(item: StatementInvoiceItem?) {
        _uiState.value = _uiState.value.copy(selectedInvoice = item)
    }

    fun recordInvoicePayment(
        amount: BigDecimal,
        paidAt: LocalDate,
        sourceAccountId: Long?,
    ): Boolean {
        val invoice = _uiState.value.selectedInvoice?.invoice ?: return false
        return store.recordInvoicePayment(invoice, amount, paidAt, sourceAccountId)
            .also { saved -> if (saved) refresh() }
    }

    fun deleteInvoicePayment(paymentId: Long): Boolean {
        val invoice = _uiState.value.selectedInvoice?.invoice ?: return false
        return store.deleteInvoicePayment(invoice, paymentId)
            .also { deleted -> if (deleted) refresh() }
    }

    fun adjustInvoiceTotal(officialTotal: BigDecimal): Boolean {
        val invoice = _uiState.value.selectedInvoice?.invoice ?: return false
        return store.adjustInvoiceTotal(invoice, officialTotal)
            .also { adjusted -> if (adjusted) refresh() }
    }

    fun onInvoiceChanged() {
        refresh()
    }

    private fun selectMonth(period: YearMonth) {
        val current = _uiState.value
        val statement = MonthlyStatementCalculator.calculate(period, statementTransactions())
        _uiState.value = current.copy(
            selectedMonth = period,
            statement = statement,
            visibleGroups = statement.groups,
            generalProjectedBalance = LoadState.Loading,
        ).withVisibleGroups()
        loadProjectedBalance()
    }

    private fun updateFilters(update: MonthlyStatementUiState.() -> MonthlyStatementUiState) {
        _uiState.value = _uiState.value.update().withVisibleGroups()
    }

    private fun MonthlyStatementUiState.withVisibleGroups(): MonthlyStatementUiState = copy(
        visibleGroups = statement.groups.mapNotNull { group ->
            group.copy(
                transactions = group.transactions.filter { transaction ->
                    (!pendingOnly || transaction.status == TransactionStatus.PENDING) &&
                        (
                            (!withoutCategoryOnly && !withoutSubcategoryOnly) ||
                                (withoutCategoryOnly &&
                                    transaction.category == TransactionCategory.UNCATEGORIZED) ||
                                (withoutSubcategoryOnly &&
                                    transaction.category != TransactionCategory.UNCATEGORIZED &&
                                    transaction.subcategory.isNullOrBlank())
                            )
                },
            ).takeIf { it.transactions.isNotEmpty() }
        },
    )

    private fun loadState(period: YearMonth): MonthlyStatementUiState {
        transactions = store.recentTransactions(limit = 10_000)
        val creditCardAccounts = store.financialAccounts()
            .filter { it.type == FinancialAccountType.CREDIT_CARD }
        val invoicesByAccount = creditCardAccounts.flatMap { account ->
            store.creditCardInvoices(account.id).map { account to it }
        }
        invoiceItems = invoicesByAccount.mapNotNull { (account, invoice) ->
            val dueDate = invoice.dueDate ?: return@mapNotNull null
            if (invoice.total.signum() == 0) return@mapNotNull null
            val isCredit = invoice.total.signum() < 0
            StatementInvoiceItem(
                account = account,
                invoice = invoice,
                transaction = FinancialTransactionRecord(
                    id = -invoice.id,
                    sourceEventId = null,
                    direction = if (isCredit) FinancialTransactionDirection.INCOME
                    else FinancialTransactionDirection.EXPENSE,
                    type = if (isCredit) FinancialTransactionType.IMPORTED_INCOME
                    else FinancialTransactionType.IMPORTED_EXPENSE,
                    amount = invoice.total.abs().toPlainString(),
                    occurredAt = dueDate.atTime(23, 59, 59).toString(),
                    description = "Fatura ${account.name}",
                    sourcePackage = "credit-card-invoice",
                    status = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                        TransactionStatus.REALIZED
                    } else TransactionStatus.PENDING,
                    account = account.name,
                    accountId = account.id,
                    invoiceId = invoice.id,
                ),
            )
        }
        creditCardAccountIds = creditCardAccounts.map { it.id }.toSet()
        val consolidatedInvoiceIds = invoiceItems.map { it.invoice.id }.toSet()
        val unconsolidatedCount = transactions.count { transaction ->
            val isCardPurchase = transaction.type == FinancialTransactionType.CARD_PURCHASE ||
                (transaction.accountId != null && transaction.accountId in creditCardAccountIds)
            isCardPurchase && (
                transaction.invoiceId == null || transaction.invoiceId !in consolidatedInvoiceIds
                )
        }
        val statement = MonthlyStatementCalculator.calculate(period, statementTransactions())
        return MonthlyStatementUiState(
            selectedMonth = period,
            statement = statement,
            visibleGroups = statement.groups,
            invoiceItemByTransactionId = invoiceItems.associateBy { it.transaction.id },
            unconsolidatedCardTransactionCount = unconsolidatedCount,
            customCategories = FinancialTransactionDirection.entries.associateWith {
                direction -> store.customCategories(direction)
            },
        )
    }

    private fun statementTransactions(): List<FinancialTransactionRecord> =
        transactions.filterNot { transaction ->
            transaction.type == FinancialTransactionType.CARD_PURCHASE ||
                (transaction.accountId != null && transaction.accountId in creditCardAccountIds)
        } + invoiceItems.map { it.transaction }

    private fun loadProjectedBalance() {
        balanceJob?.cancel()
        val period = _uiState.value.selectedMonth
        _uiState.value = _uiState.value.copy(generalProjectedBalance = LoadState.Loading)
        balanceJob = viewModelScope.launch {
            val result = try {
                LoadState.Ready(
                    withContext(Dispatchers.IO) {
                        store.generalProjectedBalance(period.atEndOfMonth())
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LoadState.Failed
            }
            if (_uiState.value.selectedMonth == period) {
                _uiState.value = _uiState.value.copy(generalProjectedBalance = result)
            }
        }
    }
}
