package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.TransactionStatus
import br.com.assistentefinanceiro.ui.screens.LoadState
import br.com.assistentefinanceiro.ui.screens.PlanningItem
import br.com.assistentefinanceiro.ui.screens.consolidatedTransactions
import br.com.assistentefinanceiro.ui.screens.transactionEffectiveDate
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PlanningUiState(
    val horizonDays: Int,
    val visibleItems: List<PlanningItem>,
    val income: BigDecimal,
    val expense: BigDecimal,
    val net: BigDecimal,
    val projectedBalance: LoadState<BigDecimal> = LoadState.Loading,
)

internal class PlanningViewModel(
    private val store: DiagnosticStore,
) : ViewModel() {
    private val today = LocalDate.now()
    private val allPending = consolidatedTransactions(store)
        .asSequence()
        .filter { it.status == TransactionStatus.PENDING }
        .mapNotNull { transaction ->
            transactionEffectiveDate(transaction)?.let { PlanningItem(transaction, it) }
        }
        .filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(90)) }
        .sortedBy { it.date }
        .toList()
    private var balanceJob: Job? = null

    private val _uiState = MutableStateFlow(buildState(30))
    val uiState: StateFlow<PlanningUiState> = _uiState.asStateFlow()

    init {
        loadProjectedBalance()
    }

    fun setHorizonDays(days: Int) {
        _uiState.value = buildState(days)
        loadProjectedBalance()
    }

    private fun buildState(days: Int): PlanningUiState {
        val visible = allPending.filter { !it.date.isAfter(today.plusDays(days.toLong())) }
        val income = visible.filter {
            it.transaction.direction == FinancialTransactionDirection.INCOME
        }.sumOf { it.transaction.amount.toBigDecimal() }
        val expense = visible.filter {
            it.transaction.direction == FinancialTransactionDirection.EXPENSE
        }.sumOf { it.transaction.amount.toBigDecimal() }
        return PlanningUiState(
            horizonDays = days,
            visibleItems = visible,
            income = income,
            expense = expense,
            net = income - expense,
        )
    }

    private fun loadProjectedBalance() {
        balanceJob?.cancel()
        val days = _uiState.value.horizonDays
        _uiState.value = _uiState.value.copy(projectedBalance = LoadState.Loading)
        balanceJob = viewModelScope.launch {
            val result = try {
                LoadState.Ready(
                    withContext(Dispatchers.IO) {
                        store.generalProjectedBalance(today.plusDays(days.toLong()))
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LoadState.Failed
            }
            if (_uiState.value.horizonDays == days) {
                _uiState.value = _uiState.value.copy(projectedBalance = result)
            }
        }
    }
}
