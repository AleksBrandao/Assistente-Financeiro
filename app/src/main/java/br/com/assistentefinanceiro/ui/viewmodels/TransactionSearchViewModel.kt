package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionSeriesScope
import br.com.assistentefinanceiro.notifications.TransactionStatus
import br.com.assistentefinanceiro.ui.screens.transactionEffectiveDate
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TransactionSearchUiState(
    val query: String = "",
    val fromDate: String = "",
    val toDate: String = "",
    val statusFilter: TransactionStatus? = null,
    val results: List<FinancialTransactionRecord> = emptyList(),
    val editingTransaction: FinancialTransactionRecord? = null,
    val customCategories: Map<FinancialTransactionDirection, List<String>> = emptyMap(),
)

internal class TransactionSearchViewModel(
    private val repository: FinancialRepository,
) : ViewModel() {
    private var transactions = repository.recentTransactions(10_000)
    private val _uiState = MutableStateFlow(filteredState(TransactionSearchUiState()))
    val uiState: StateFlow<TransactionSearchUiState> = _uiState.asStateFlow()

    fun setQuery(value: String) = updateFilters { copy(query = value) }
    fun setFromDate(value: String) = updateFilters { copy(fromDate = value) }
    fun setToDate(value: String) = updateFilters { copy(toDate = value) }
    fun setStatusFilter(value: TransactionStatus?) = updateFilters { copy(statusFilter = value) }

    fun clearSearch() {
        _uiState.value = filteredState(
            _uiState.value.copy(query = "", fromDate = "", toDate = "", statusFilter = null),
        )
    }

    fun editTransaction(transaction: FinancialTransactionRecord?) {
        _uiState.value = _uiState.value.copy(editingTransaction = transaction)
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
            repository.updateTransactionDetails(
                transaction.id, description, category, customCategory, subcategory, status,
                amount, dueDate, plannedPaymentDate, paidAt, applyToFuture, seriesScope,
            )
        ) {
            transactions = repository.recentTransactions(10_000)
            _uiState.value = filteredState(_uiState.value.copy(editingTransaction = null))
        }
    }

    private fun updateFilters(update: TransactionSearchUiState.() -> TransactionSearchUiState) {
        _uiState.value = filteredState(_uiState.value.update())
    }

    private fun filteredState(state: TransactionSearchUiState): TransactionSearchUiState {
        val from = state.fromDate.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val to = state.toDate.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        return state.copy(
            results = transactions.filter { transaction ->
                val date = transactionEffectiveDate(transaction)
                transaction.description.contains(state.query.trim(), ignoreCase = true) &&
                    (state.statusFilter == null || transaction.status == state.statusFilter) &&
                    (from == null || (date != null && !date.isBefore(from))) &&
                    (to == null || (date != null && !date.isAfter(to)))
            }.sortedByDescending { transactionEffectiveDate(it) },
            customCategories = FinancialTransactionDirection.entries.associateWith { direction ->
                repository.customCategories(direction)
            },
        )
    }
}
