package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.MonthlyBudgetCalculator
import br.com.assistentefinanceiro.notifications.MonthlyBudgetProgress
import br.com.assistentefinanceiro.notifications.MonthlyBudgetRecord
import br.com.assistentefinanceiro.notifications.MonthlyCategorySpending
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.ui.screens.CategoryChoice
import br.com.assistentefinanceiro.ui.screens.transactionEffectiveDate
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MonthlyBudgetUiState(
    val selectedMonth: YearMonth,
    val budgets: List<MonthlyBudgetRecord>,
    val transactions: List<FinancialTransactionRecord>,
    val progress: List<MonthlyBudgetProgress>,
    val categorySpending: List<MonthlyCategorySpending>,
    val editingCategory: CategoryChoice? = null,
    val editingTotal: Boolean = false,
    val message: String? = null,
) {
    val totalProgress: MonthlyBudgetProgress?
        get() = progress.firstOrNull { it.category == null }
    val categoryProgress: List<MonthlyBudgetProgress>
        get() = progress.filter { it.category != null }
    val expenseCategories: List<TransactionCategory>
        get() = TransactionCategory.availableFor(FinancialTransactionDirection.EXPENSE)
    val configuredKeys: Set<String>
        get() = categoryProgress.mapNotNull { it.categoryKey }.toSet()
    val customExpenseCategories: List<String>
        get() = transactions.asSequence()
            .filter { it.direction == FinancialTransactionDirection.EXPENSE }
            .mapNotNull { it.customCategory?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
            .toList()
    val includedTransactions: List<FinancialTransactionRecord>
        get() {
            val choice = editingCategory
            val category = choice?.category
            val customCategory = choice?.customCategory
            return transactions.filter { transaction ->
                transaction.direction == FinancialTransactionDirection.EXPENSE &&
                    transactionEffectiveDate(transaction)?.let { YearMonth.from(it) } ==
                    selectedMonth &&
                    if (choice == null) true
                    else if (customCategory != null) {
                        transaction.customCategory == customCategory
                    } else {
                        transaction.customCategory == null && transaction.category == category
                    }
            }
        }
}

internal class MonthlyBudgetViewModel(
    private val store: DiagnosticStore,
) : ViewModel() {
    private var transactions = store.recentTransactions(10_000)

    private val _uiState = MutableStateFlow(buildState(YearMonth.now()))
    val uiState: StateFlow<MonthlyBudgetUiState> = _uiState.asStateFlow()

    fun showPreviousMonth() = selectMonth(_uiState.value.selectedMonth.minusMonths(1))

    fun showNextMonth() = selectMonth(_uiState.value.selectedMonth.plusMonths(1))

    fun editTotal() {
        _uiState.value = _uiState.value.copy(editingTotal = true, editingCategory = null)
    }

    fun editCategory(choice: CategoryChoice) {
        _uiState.value = _uiState.value.copy(editingCategory = choice, editingTotal = false)
    }

    fun dismissEditor() {
        _uiState.value = _uiState.value.copy(editingCategory = null, editingTotal = false)
    }

    fun copyPreviousMonth() {
        val period = _uiState.value.selectedMonth
        val copied = store.copyMonthlyBudgets(period.minusMonths(1), period)
        val message = if (copied > 0) {
            "$copied orçamento(s) copiado(s) do mês anterior."
        } else {
            "O mês anterior não possui orçamento."
        }
        if (copied > 0) reload(period, message) else {
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    fun saveBudget(amount: BigDecimal) {
        val state = _uiState.value
        val choice = state.editingCategory
        if (
            store.saveMonthlyBudget(
                state.selectedMonth,
                choice?.category,
                amount,
                choice?.customCategory,
            )
        ) {
            reload(state.selectedMonth)
        }
    }

    fun deleteBudget() {
        val state = _uiState.value
        val choice = state.editingCategory
        store.deleteMonthlyBudget(
            state.selectedMonth,
            choice?.category,
            choice?.customCategory,
        )
        reload(state.selectedMonth)
    }

    private fun selectMonth(period: YearMonth) {
        _uiState.value = buildState(period)
    }

    private fun reload(period: YearMonth, message: String? = null) {
        transactions = store.recentTransactions(10_000)
        _uiState.value = buildState(period).copy(message = message)
    }

    private fun buildState(period: YearMonth): MonthlyBudgetUiState {
        val budgets = store.monthlyBudgets(period)
        return MonthlyBudgetUiState(
            selectedMonth = period,
            budgets = budgets,
            transactions = transactions,
            progress = MonthlyBudgetCalculator.calculate(period, budgets, transactions),
            categorySpending = MonthlyBudgetCalculator.spendingByCategory(period, transactions),
        )
    }
}
