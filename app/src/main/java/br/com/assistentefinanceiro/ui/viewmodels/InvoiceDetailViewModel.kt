package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.InvoicePaymentRecord
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionSeriesScope
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class InvoiceDetailUiState(
    val transactions: List<FinancialTransactionRecord>,
    val payments: List<InvoicePaymentRecord>,
    val bankAccounts: List<FinancialAccountRecord>,
    val referenceMonth: YearMonth,
    val addingPayment: Boolean = false,
    val paymentAmount: String,
    val paymentDate: String,
    val sourceAccountId: Long?,
    val sourceAccountMenuExpanded: Boolean = false,
    val deletingPayment: InvoicePaymentRecord? = null,
    val adjustingInvoice: Boolean = false,
    val editingTransaction: FinancialTransactionRecord? = null,
    val officialTotal: String,
    val customCategories: Map<FinancialTransactionDirection, List<String>> = emptyMap(),
) {
    val officialValue: BigDecimal?
        get() = decimalValue(officialTotal)
    val parsedPaymentAmount: BigDecimal?
        get() = decimalValue(paymentAmount)
    val parsedPaymentDate: LocalDate?
        get() = runCatching { LocalDate.parse(paymentDate) }.getOrNull()

    companion object {
        private fun decimalValue(value: String): BigDecimal? = if (',' in value) {
            value.replace(".", "").replace(',', '.').toBigDecimalOrNull()
        } else value.toBigDecimalOrNull()
    }
}

internal class InvoiceDetailViewModel(
    private val store: DiagnosticStore,
    private val invoice: CreditCardInvoiceRecord,
) : ViewModel() {
    private val bankAccounts = store.financialAccounts().filter {
        it.type == FinancialAccountType.BANK_ACCOUNT
    }
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<InvoiceDetailUiState> = _uiState.asStateFlow()

    fun setAdjustingInvoice(adjusting: Boolean) {
        _uiState.value = _uiState.value.copy(adjustingInvoice = adjusting)
    }

    fun setOfficialTotal(value: String) {
        _uiState.value = _uiState.value.copy(
            officialTotal = value.filter { it.isDigit() || it == ',' || it == '.' },
        )
    }

    fun submitAdjustment(action: (BigDecimal) -> Boolean): Boolean {
        val value = _uiState.value.officialValue ?: return false
        return action(value).also { adjusted ->
            if (adjusted) _uiState.value = _uiState.value.copy(adjustingInvoice = false)
        }
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
    ): Boolean {
        val transaction = _uiState.value.editingTransaction ?: return false
        return store.updateTransactionDetails(
            transaction.id, description, category, customCategory, subcategory, status,
            amount, dueDate, plannedPaymentDate, paidAt, applyToFuture, seriesScope,
        ).also { saved ->
            if (saved) _uiState.value = _uiState.value.copy(editingTransaction = null)
        }
    }

    fun setAddingPayment(adding: Boolean) {
        _uiState.value = _uiState.value.copy(addingPayment = adding)
    }

    fun setPaymentAmount(value: String) {
        _uiState.value = _uiState.value.copy(
            paymentAmount = value.filter { it.isDigit() || it in ",." }.take(16),
        )
    }

    fun setPaymentDate(value: String) {
        _uiState.value = _uiState.value.copy(paymentDate = value)
    }

    fun setSourceAccountMenuExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(sourceAccountMenuExpanded = expanded)
    }

    fun selectSourceAccount(accountId: Long) {
        _uiState.value = _uiState.value.copy(
            sourceAccountId = accountId,
            sourceAccountMenuExpanded = false,
        )
    }

    fun submitPayment(action: (BigDecimal, LocalDate, Long?) -> Boolean): Boolean {
        val state = _uiState.value
        val amount = state.parsedPaymentAmount ?: return false
        val date = state.parsedPaymentDate ?: return false
        return action(amount, date, state.sourceAccountId).also { saved ->
            if (saved) _uiState.value = _uiState.value.copy(addingPayment = false)
        }
    }

    fun requestDeletePayment(payment: InvoicePaymentRecord?) {
        _uiState.value = _uiState.value.copy(deletingPayment = payment)
    }

    fun confirmDeletePayment(action: (Long) -> Boolean): Boolean {
        val payment = _uiState.value.deletingPayment ?: return false
        return action(payment.id).also { deleted ->
            if (deleted) _uiState.value = _uiState.value.copy(deletingPayment = null)
        }
    }

    private fun loadState(): InvoiceDetailUiState = InvoiceDetailUiState(
        transactions = store.invoiceTransactions(invoice.id),
        payments = store.invoicePayments(invoice),
        bankAccounts = bankAccounts,
        referenceMonth = invoice.dueDate?.let(YearMonth::from) ?: invoice.closingPeriod,
        paymentAmount = invoice.outstandingAmount.toPlainString().replace('.', ','),
        paymentDate = LocalDate.now().toString(),
        sourceAccountId = bankAccounts.singleOrNull()?.id,
        officialTotal = invoice.total.toPlainString().replace('.', ','),
        customCategories = FinancialTransactionDirection.entries.associateWith { direction ->
            store.customCategories(direction)
        },
    )
}
