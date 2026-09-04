package br.com.assistentefinanceiro.openfinance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object PluggySandboxFeature {
    const val isEnabled: Boolean = true

    @Composable
    fun Screen(
        repository: FinancialRepository,
        onBack: () -> Unit,
    ) {
        PluggySandboxScreen(repository = repository, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluggySandboxScreen(
    repository: FinancialRepository,
    onBack: () -> Unit,
) {
    val client = remember { PluggyReadOnlyClient() }
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var itemId by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PluggySandboxPreview?>(null) }
    var reconciliation by remember { mutableStateOf<PluggyReconciliationPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Open Finance (Teste)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Consulta somente leitura",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "A apiKey e o Item ID são usados apenas em memória nesta tela. " +
                        "Nenhum dado é gravado no banco do aplicativo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "A reconciliação abaixo compara somente com os dados existentes neste " +
                        "Assistente Financeiro (Teste). Nenhum vínculo é salvo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pluggy apiKey temporária") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !loading,
                )
            }

            item {
                OutlinedTextField(
                    value = itemId,
                    onValueChange = { itemId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Item ID") },
                    singleLine = true,
                    enabled = !loading,
                )
            }

            item {
                Button(
                    onClick = {
                        val key = apiKey.trim()
                        val item = itemId.trim()
                        loading = true
                        preview = null
                        reconciliation = null
                        error = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val remote = client.fetchPreview(key, item)
                                    val localAccounts = repository.financialAccounts()
                                    val localTransactions = repository.granularTransactions()
                                    val invoicesByAccount = localAccounts
                                        .filter { it.type == FinancialAccountType.CREDIT_CARD }
                                        .associate { account ->
                                            account.id to repository.creditCardInvoices(account.id)
                                        }
                                    val localPreview = PluggyReconciliationEngine.reconcile(
                                        PluggyReconciliationInput(
                                            pluggyAccounts = remote.accounts.map { accountPreview ->
                                                PluggyAccountDataset(
                                                    account = accountPreview.account,
                                                    transactions = accountPreview.transactions,
                                                    bills = accountPreview.bills,
                                                )
                                            },
                                            localAccounts = localAccounts,
                                            localTransactions = localTransactions,
                                            localInvoicesByAccount = invoicesByAccount,
                                        ),
                                    )
                                    remote to localPreview
                                }
                            }
                            result.onSuccess { (remote, localPreview) ->
                                preview = remote
                                reconciliation = localPreview
                                apiKey = ""
                            }.onFailure { throwable ->
                                error = throwable.toSafeMessage()
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && apiKey.isNotBlank() && itemId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Consultar Pluggy")
                    }
                }
            }

            error?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            preview?.let { result ->
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Item: ${result.itemStatus}" +
                            result.executionStatus?.let { " • $it" }.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${result.accounts.size} conta(s) encontrada(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(result.accounts) { accountPreview ->
                    PluggyAccountPreviewCard(accountPreview)
                }
            }

            reconciliation?.let { result ->
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Reconciliação local (prévia)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Somente análise. Nenhuma conta, transação, fatura ou categoria foi alterada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ReconciliationSummaryCard(result)
                }
                items(result.accounts) { accountResult ->
                    ReconciliationAccountCard(accountResult)
                }
                item {
                    Text(
                        "Fase 3 concluída em modo somente leitura. Correspondências marcadas como " +
                            "'Revisar' ou 'Sem correspondência' não são inferidas como duplicidade.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluggyAccountPreviewCard(preview: PluggySandboxAccountPreview) {
    val account = preview.account
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(account.name, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(account.type.name, account.subtype).joinToString(" • "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Saldo/uso informado: ${formatMoney(account.balance, account.currencyCode)}",
                style = MaterialTheme.typography.bodyMedium,
            )

            account.creditData?.let { credit ->
                credit.creditLimit?.let {
                    KeyValueRow("Limite total", formatMoney(it, account.currencyCode))
                }
                credit.availableCreditLimit?.let {
                    KeyValueRow("Limite disponível", formatMoney(it, account.currencyCode))
                }
                credit.balanceDueDate?.let { KeyValueRow("Vencimento informado", it.toString()) }
            }
            account.bankData?.let { bank ->
                bank.closingBalance?.let {
                    KeyValueRow("Closing balance", formatMoney(it, account.currencyCode))
                }
                bank.overdraftContractedLimit?.let {
                    KeyValueRow("Cheque especial", formatMoney(it, account.currencyCode))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            KeyValueRow("Transações", preview.transactionCount.toString())
            KeyValueRow("POSTED / PENDING", "${preview.postedCount} / ${preview.pendingCount}")
            KeyValueRow("Parceladas", preview.installmentCount.toString())
            KeyValueRow("PIX", preview.pixCount.toString())
            if (account.type == PluggyAccountType.CREDIT) {
                KeyValueRow("Faturas fechadas", preview.bills.size.toString())
                preview.bills.maxByOrNull { it.dueDate }?.let { bill ->
                    KeyValueRow(
                        "Última fatura retornada",
                        "${bill.dueDate} • ${formatMoney(bill.totalAmount, account.currencyCode)}",
                    )
                }
            }
        }
    }
}

@Composable
private fun ReconciliationSummaryCard(result: PluggyReconciliationPreview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyValueRow("Vínculo forte", result.strongAccounts.toString())
            KeyValueRow("Vínculo provável", result.probableAccounts.toString())
            KeyValueRow("Revisar", result.reviewAccounts.toString())
            KeyValueRow("Sem correspondência", result.unmatchedAccounts.toString())
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            KeyValueRow("Categorias Pluggy distintas", result.distinctPluggyCategories.toString())
            KeyValueRow("Correspondência direta de categoria", result.directCategoryMatches.toString())
        }
    }
}

@Composable
private fun ReconciliationAccountCard(result: PluggyAccountReconciliation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(result.pluggyAccountName, style = MaterialTheme.typography.titleMedium)
            Text(
                reconciliationStatusLabel(result.status),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = when (result.status) {
                    PluggyReconciliationStatus.STRONG,
                    PluggyReconciliationStatus.PROBABLE -> MaterialTheme.colorScheme.primary
                    PluggyReconciliationStatus.REVIEW -> MaterialTheme.colorScheme.tertiary
                    PluggyReconciliationStatus.UNMATCHED -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            result.localAccountName?.let {
                KeyValueRow("Conta local sugerida", it)
            }
            if (result.localAccountName == null && result.compatibleCandidateCount > 0) {
                KeyValueRow("Contas locais compatíveis", result.compatibleCandidateCount.toString())
            }
            result.reasons.take(3).forEach { reason ->
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            KeyValueRow(
                "Transações coincidem / revisar / sem correspondência",
                "${result.transactionCounts.matched} / ${result.transactionCounts.review} / ${result.transactionCounts.unmatched}",
            )
            if (result.pluggyAccountType == PluggyAccountType.CREDIT) {
                KeyValueRow(
                    "Faturas coincidem / revisar / sem correspondência",
                    "${result.billCounts.matched} / ${result.billCounts.review} / ${result.billCounts.unmatched}",
                )
            }
            KeyValueRow("Categorias Pluggy nesta conta", result.pluggyCategoryCount.toString())
        }
    }
}

private fun reconciliationStatusLabel(status: PluggyReconciliationStatus): String = when (status) {
    PluggyReconciliationStatus.STRONG -> "Vínculo forte"
    PluggyReconciliationStatus.PROBABLE -> "Vínculo provável"
    PluggyReconciliationStatus.REVIEW -> "Revisar"
    PluggyReconciliationStatus.UNMATCHED -> "Sem correspondência"
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Throwable.toSafeMessage(): String = when (this) {
    is PluggyApiException -> buildString {
        append("Pluggy HTTP ")
        append(httpStatus)
        codeDescription?.let { append(" • ").append(it) }
        append(": ").append(message)
    }
    is IllegalArgumentException -> message ?: "Parâmetro inválido"
    else -> "Falha ao consultar a Pluggy: ${message ?: this::class.simpleName.orEmpty()}"
}

private fun formatMoney(value: BigDecimal, currencyCode: String): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    runCatching { formatter.currency = Currency.getInstance(currencyCode) }
    return formatter.format(value)
}
