package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CardInvoicesUiState(
    val selectedMonth: YearMonth,
    val invoices: List<CreditCardInvoiceRecord>,
    val visibleInvoice: CreditCardInvoiceRecord?,
    val selectedInvoice: CreditCardInvoiceRecord? = null,
)

internal class CardInvoicesViewModel(
    private val store: DiagnosticStore,
    private val account: FinancialAccountRecord,
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState(YearMonth.now()))
    val uiState: StateFlow<CardInvoicesUiState> = _uiState.asStateFlow()

    fun showPreviousMonth() = selectMonth(_uiState.value.selectedMonth.minusMonths(1))
    fun showNextMonth() = selectMonth(_uiState.value.selectedMonth.plusMonths(1))

    fun selectInvoice(invoice: CreditCardInvoiceRecord?) {
        _uiState.value = _uiState.value.copy(selectedInvoice = invoice)
    }

    fun recordPayment(amount: BigDecimal, paidAt: LocalDate, sourceAccountId: Long?): Boolean {
        val invoice = _uiState.value.selectedInvoice ?: return false
        return store.recordInvoicePayment(invoice, amount, paidAt, sourceAccountId)
            .also { saved -> if (saved) reloadSelected(invoice.id) }
    }

    fun deletePayment(paymentId: Long): Boolean {
        val invoice = _uiState.value.selectedInvoice ?: return false
        return store.deleteInvoicePayment(invoice, paymentId)
            .also { deleted -> if (deleted) reloadSelected(invoice.id) }
    }

    fun adjustInvoice(officialTotal: BigDecimal): Boolean {
        val invoice = _uiState.value.selectedInvoice ?: return false
        return store.adjustInvoiceTotal(invoice, officialTotal).also { adjusted ->
            if (adjusted) reload(selectedInvoiceId = null)
        }
    }

    fun onTransactionChanged() {
        reload(selectedInvoiceId = null)
    }

    private fun selectMonth(period: YearMonth) {
        val invoices = _uiState.value.invoices
        _uiState.value = _uiState.value.copy(
            selectedMonth = period,
            visibleInvoice = invoiceFor(invoices, period),
        )
    }

    private fun reloadSelected(invoiceId: Long) = reload(selectedInvoiceId = invoiceId)

    private fun reload(selectedInvoiceId: Long?) {
        val period = _uiState.value.selectedMonth
        val invoices = store.creditCardInvoices(account.id)
        _uiState.value = CardInvoicesUiState(
            selectedMonth = period,
            invoices = invoices,
            visibleInvoice = invoiceFor(invoices, period),
            selectedInvoice = selectedInvoiceId?.let { id -> invoices.firstOrNull { it.id == id } },
        )
    }

    private fun loadState(period: YearMonth): CardInvoicesUiState {
        val invoices = store.creditCardInvoices(account.id)
        return CardInvoicesUiState(
            selectedMonth = period,
            invoices = invoices,
            visibleInvoice = invoiceFor(invoices, period),
        )
    }

    private fun invoiceFor(
        invoices: List<CreditCardInvoiceRecord>,
        period: YearMonth,
    ): CreditCardInvoiceRecord? = invoices.firstOrNull { invoice ->
        (invoice.dueDate?.let(YearMonth::from) ?: invoice.closingPeriod) == period
    }
}
