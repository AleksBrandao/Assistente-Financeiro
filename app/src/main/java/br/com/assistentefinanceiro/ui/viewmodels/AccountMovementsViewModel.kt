package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.notifications.AccountBalanceSummary
import br.com.assistentefinanceiro.notifications.AccountMovementDirection
import br.com.assistentefinanceiro.notifications.AccountMovementRecord
import br.com.assistentefinanceiro.notifications.AccountMovementType
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionSeriesScope
import br.com.assistentefinanceiro.notifications.TransactionStatus
import br.com.assistentefinanceiro.ui.screens.AccountLedgerItem
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AccountMovementsUiState(
    val selectedMonth: YearMonth,
    val balance: AccountBalanceSummary,
    val visibleLedgerItems: List<AccountLedgerItem>,
    val addingTransaction: Boolean = false,
    val editingTransaction: FinancialTransactionRecord? = null,
    val deletingTransaction: FinancialTransactionRecord? = null,
    val deletingSeriesScope: TransactionSeriesScope = TransactionSeriesScope.ONLY_THIS,
    val deletingTransfer: AccountMovementRecord? = null,
    val customCategories: Map<FinancialTransactionDirection, List<String>> = emptyMap(),
)

internal class AccountMovementsViewModel(
    private val store: DiagnosticStore,
    private val account: FinancialAccountRecord,
) : ViewModel() {
    private var ledgerItems: List<AccountLedgerItem> = emptyList()
    private val _uiState = MutableStateFlow(loadState(YearMonth.now()))
    val uiState: StateFlow<AccountMovementsUiState> = _uiState.asStateFlow()

    fun showPreviousMonth() = selectMonth(_uiState.value.selectedMonth.minusMonths(1))
    fun showNextMonth() = selectMonth(_uiState.value.selectedMonth.plusMonths(1))

    fun setAddingTransaction(adding: Boolean) {
        _uiState.value = _uiState.value.copy(addingTransaction = adding)
    }

    fun editTransaction(transaction: FinancialTransactionRecord?) {
        _uiState.value = _uiState.value.copy(editingTransaction = transaction)
    }

    fun requestDelete(transaction: FinancialTransactionRecord, scope: TransactionSeriesScope) {
        _uiState.value = _uiState.value.copy(
            editingTransaction = null,
            deletingTransaction = transaction,
            deletingSeriesScope = scope,
        )
    }

    fun dismissDeleteTransaction() {
        _uiState.value = _uiState.value.copy(deletingTransaction = null)
    }

    fun requestDeleteTransfer(movement: AccountMovementRecord?) {
        _uiState.value = _uiState.value.copy(deletingTransfer = movement)
    }

    fun recordManualTransaction(
        direction: FinancialTransactionDirection,
        amount: BigDecimal,
        date: LocalDate,
        description: String,
        status: TransactionStatus,
        occurrences: Int,
    ): Boolean = store.recordManualTransaction(
        account.id, direction, amount, date, description, status, occurrences,
    ).also { saved -> if (saved) reload() }

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
    ): Boolean {
        val transaction = _uiState.value.editingTransaction ?: return false
        return store.updateTransactionDetails(
            transaction.id, description, category, customCategory, subcategory, status,
            amount, dueDate, plannedPaymentDate, paidAt, applyToFuture, seriesScope,
        ).also { saved -> if (saved) reload() }
    }

    fun confirmDeleteTransaction(): Boolean {
        val state = _uiState.value
        val transaction = state.deletingTransaction ?: return false
        return store.deleteManualTransaction(transaction.id, state.deletingSeriesScope)
            .also { deleted -> if (deleted) reload() }
    }

    fun confirmDeleteTransfer(): Boolean {
        val movement = _uiState.value.deletingTransfer ?: return false
        return store.deleteTransfer(movement.id).also { deleted -> if (deleted) reload() }
    }

    private fun selectMonth(period: YearMonth) {
        _uiState.value = _uiState.value.copy(
            selectedMonth = period,
            visibleLedgerItems = visibleItems(period),
        )
    }

    private fun reload() {
        _uiState.value = loadState(_uiState.value.selectedMonth)
    }

    private fun loadState(period: YearMonth): AccountMovementsUiState {
        val movements = store.accountMovements(account.id)
        val transactions = store.recentTransactions(10_000).filter { it.accountId == account.id }
        val transactionItems = transactions.mapNotNull { transaction ->
            val occurredAt = runCatching { LocalDateTime.parse(transaction.occurredAt) }
                .getOrNull() ?: return@mapNotNull null
            AccountLedgerItem(
                key = "transaction-${transaction.id}",
                occurredAt = occurredAt,
                direction = transaction.direction,
                amount = transaction.amount.toBigDecimalOrNull() ?: return@mapNotNull null,
                description = transaction.description,
                detail = listOf(
                    transaction.categoryDisplayName,
                    if (transaction.status == TransactionStatus.PENDING) "Pendente" else "Realizada",
                ).joinToString(" · "),
                transaction = transaction,
            )
        }
        val movementItems = movements.map { movement ->
            AccountLedgerItem(
                key = "movement-${movement.id}",
                occurredAt = movement.occurredAt.atTime(23, 59, 59),
                direction = if (movement.direction == AccountMovementDirection.CREDIT) {
                    FinancialTransactionDirection.INCOME
                } else FinancialTransactionDirection.EXPENSE,
                amount = movement.amount,
                description = movement.description,
                detail = listOfNotNull(
                    movement.relatedAccountName,
                    if (movement.type == AccountMovementType.TRANSFER) {
                        "Transferência"
                    } else "Pagamento de fatura",
                ).joinToString(" · "),
                movement = movement,
            )
        }
        ledgerItems = (transactionItems + movementItems).sortedWith(
            compareByDescending<AccountLedgerItem> { it.occurredAt }
                .thenByDescending { it.transaction?.id ?: it.movement?.id ?: 0L },
        )
        return AccountMovementsUiState(
            selectedMonth = period,
            balance = store.accountBalance(account),
            visibleLedgerItems = visibleItems(period),
            customCategories = FinancialTransactionDirection.entries.associateWith { direction ->
                store.customCategories(direction)
            },
        )
    }

    private fun visibleItems(period: YearMonth): List<AccountLedgerItem> =
        ledgerItems.filter { YearMonth.from(it.occurredAt) == period }
}
