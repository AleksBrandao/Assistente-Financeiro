package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.MonthlyStatement
import br.com.assistentefinanceiro.notifications.MonthlyStatementCalculator
import br.com.assistentefinanceiro.ui.screens.LoadState
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

internal data class AnnualSummaryUiState(
    val selectedYear: Int,
    val rows: List<Pair<YearMonth, MonthlyStatement>>,
    val projectedBalances: LoadState<Map<LocalDate, BigDecimal>> = LoadState.Loading,
)

internal class AnnualSummaryViewModel(
    private val repository: FinancialRepository,
) : ViewModel() {
    private var balanceJob: Job? = null

    private val _uiState = MutableStateFlow(buildState(LocalDate.now().year))
    val uiState: StateFlow<AnnualSummaryUiState> = _uiState.asStateFlow()

    init {
        loadProjectedBalances()
    }

    fun showPreviousYear() = selectYear(_uiState.value.selectedYear - 1)

    fun showNextYear() = selectYear(_uiState.value.selectedYear + 1)

    private fun selectYear(year: Int) {
        _uiState.value = buildState(year)
        loadProjectedBalances()
    }

    private fun buildState(year: Int): AnnualSummaryUiState {
        val entries = repository.statementEntries()
        val periods = periodsFor(year)
        return AnnualSummaryUiState(
            selectedYear = year,
            rows = periods.map { period ->
                period to MonthlyStatementCalculator.calculateEntries(period, entries)
            },
        )
    }

    private fun loadProjectedBalances() {
        balanceJob?.cancel()
        val year = _uiState.value.selectedYear
        val periods = periodsFor(year)
        _uiState.value = _uiState.value.copy(projectedBalances = LoadState.Loading)
        balanceJob = viewModelScope.launch {
            val result = try {
                LoadState.Ready(
                    withContext(Dispatchers.IO) {
                        repository.generalProjectedBalances(periods.map { it.atEndOfMonth() })
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LoadState.Failed
            }
            if (_uiState.value.selectedYear == year) {
                _uiState.value = _uiState.value.copy(projectedBalances = result)
            }
        }
    }

    private fun periodsFor(year: Int): List<YearMonth> =
        (1..12).map { month -> YearMonth.of(year, month) }
}
