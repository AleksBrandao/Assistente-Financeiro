package br.com.assistentefinanceiro.ui.viewmodels

import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.AccountBalanceSummary
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AccountsUiState(
    val accounts: List<FinancialAccountRecord>,
    val bankBalances: Map<FinancialAccountRecord, AccountBalanceSummary>,
    val editingAccount: FinancialAccountRecord? = null,
    val creatingAccount: Boolean = false,
    val viewingInvoicesFor: FinancialAccountRecord? = null,
    val viewingMovementsFor: FinancialAccountRecord? = null,
    val creatingTransfer: Boolean = false,
)

internal class AccountsViewModel(
    private val repository: FinancialRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    fun editAccount(account: FinancialAccountRecord?) {
        _uiState.value = _uiState.value.copy(editingAccount = account)
    }

    fun setCreatingAccount(creating: Boolean) {
        _uiState.value = _uiState.value.copy(
            creatingAccount = creating,
            editingAccount = if (creating) null else _uiState.value.editingAccount,
        )
    }

    fun viewInvoices(account: FinancialAccountRecord?) {
        _uiState.value = _uiState.value.copy(viewingInvoicesFor = account)
    }

    fun viewMovements(account: FinancialAccountRecord?) {
        _uiState.value = _uiState.value.copy(viewingMovementsFor = account)
    }

    fun setCreatingTransfer(creating: Boolean) {
        _uiState.value = _uiState.value.copy(creatingTransfer = creating)
    }

    fun closeChildAndRefresh() {
        reload(
            viewingInvoicesFor = null,
            viewingMovementsFor = null,
        )
    }

    fun refresh() {
        val state = _uiState.value
        reload(
            viewingInvoicesFor = state.viewingInvoicesFor,
            viewingMovementsFor = state.viewingMovementsFor,
        )
    }

    fun saveAccount(
        account: FinancialAccountRecord,
        name: String,
        type: FinancialAccountType,
        closingDay: Int?,
        dueDay: Int?,
        isDefault: Boolean,
        cardIdentifiers: String?,
        openingBalance: BigDecimal,
        openingBalanceDate: LocalDate?,
    ) {
        val creating = _uiState.value.creatingAccount
        if (
            repository.saveFinancialAccount(
                id = account.id.takeUnless { creating },
                name = name,
                type = type,
                closingDay = closingDay,
                dueDay = dueDay,
                isDefault = isDefault,
                cardIdentifiers = cardIdentifiers,
                openingBalance = openingBalance,
                openingBalanceDate = openingBalanceDate,
            )
        ) reload()
    }

    fun recordTransfer(
        sourceAccountId: Long,
        destinationAccountId: Long,
        amount: BigDecimal,
        date: LocalDate,
        description: String,
    ) {
        if (
            repository.recordTransfer(
                sourceAccountId,
                destinationAccountId,
                amount,
                date,
                description,
            )
        ) reload()
    }

    private fun reload(
        viewingInvoicesFor: FinancialAccountRecord? = null,
        viewingMovementsFor: FinancialAccountRecord? = null,
    ) {
        _uiState.value = loadState().copy(
            viewingInvoicesFor = viewingInvoicesFor,
            viewingMovementsFor = viewingMovementsFor,
        )
    }

    private fun loadState(): AccountsUiState {
        val accounts = repository.financialAccounts()
        return AccountsUiState(
            accounts = accounts,
            bankBalances = accounts
                .filter { it.type == FinancialAccountType.BANK_ACCOUNT }
                .associateWith(repository::accountBalance),
        )
    }
}
