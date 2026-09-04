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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import br.com.assistentefinanceiro.data.ExternalAccountLinkRecord
import br.com.assistentefinanceiro.data.ExternalDataProvider
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
    var selectedForImport by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingPlan by remember { mutableStateOf<PluggyControlledImportPlan?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun launchQuery(key: String, item: String) {
        loading = true
        preview = null
        reconciliation = null
        selectedForImport = emptySet()
        pendingPlan = null
        importMessage = null
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val remote = client.fetchPreview(key, item)
                    remote to buildReconciliation(repository, remote)
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
    }

    pendingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!loading) pendingPlan = null },
            title = { Text("Confirmar importação controlada") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${plan.selectedAccounts} conta(s) selecionada(s)")
                    Text("${plan.importable} transação(ões) nova(s) serão gravadas no app de teste.")
                    Text("${plan.matchedExisting} já possuem correspondência local.")
                    Text("${plan.skippedReview} ficaram em Revisar e não serão importadas.")
                    Text("${plan.skippedPending} PENDING foram ignoradas nesta fase.")
                    if (plan.skippedCreditCardCredits > 0) {
                        Text("${plan.skippedCreditCardCredits} crédito(s) de cartão foram ignorados para evitar tratar pagamento/reembolso como compra.")
                    }
                    if (plan.skippedOutsideWindow > 0) {
                        Text("${plan.skippedOutsideWindow} ficaram fora da janela de 90 dias.")
                    }
                    Text(
                        "Os vínculos selecionados também serão confirmados. Esta ação afeta somente o Assistente Financeiro (Teste).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        val remote = preview ?: return@TextButton
                        val currentReconciliation = reconciliation ?: return@TextButton
                        pendingPlan = null
                        loading = true
                        error = null
                        importMessage = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val selectedResults = currentReconciliation.accounts.filter {
                                        it.pluggyAccountExternalId in selectedForImport &&
                                            it.localAccountId != null &&
                                            it.status in setOf(
                                                PluggyReconciliationStatus.CONFIRMED,
                                                PluggyReconciliationStatus.STRONG,
                                            )
                                    }
                                    selectedResults.forEach { account ->
                                        check(
                                            repository.saveExternalAccountLink(
                                                ExternalAccountLinkRecord(
                                                    provider = ExternalDataProvider.PLUGGY,
                                                    externalAccountId = account.pluggyAccountExternalId,
                                                    localAccountId = checkNotNull(account.localAccountId),
                                                ),
                                            ),
                                        ) { "Não foi possível confirmar um vínculo de conta" }
                                    }
                                    val imported = repository.importExternalTransactions(plan.drafts)
                                    val refreshed = buildReconciliation(repository, remote)
                                    imported to refreshed
                                }
                            }
                            result.onSuccess { (imported, refreshed) ->
                                reconciliation = refreshed
                                selectedForImport = emptySet()
                                importMessage =
                                    "Importação concluída: ${imported.imported} nova(s), " +
                                        "${imported.alreadyImported} já existente(s)."
                            }.onFailure { throwable ->
                                error = throwable.toSafeMessage()
                            }
                            loading = false
                        }
                    },
                ) {
                    Text("Confirmar")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !loading,
                    onClick = { pendingPlan = null },
                ) {
                    Text("Cancelar")
                }
            },
        )
    }

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
                Text("Consulta e reconciliação", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A apiKey e o Item ID são usados apenas em memória nesta tela. A consulta não grava dados.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "A gravação só ocorre na seção Importação controlada, após seleção e confirmação explícitas.",
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
                    onClick = { launchQuery(apiKey.trim(), itemId.trim()) },
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

            importMessage?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                    Text("Reconciliação local", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vínculos já confirmados são reutilizados; quando ainda não existe nenhuma conta local compatível, a conta pode ser criada durante a preparação da primeira importação.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { ReconciliationSummaryCard(result) }
                items(result.accounts) { accountResult ->
                    val canUseExisting = accountResult.localAccountId != null &&
                        accountResult.status in setOf(
                            PluggyReconciliationStatus.CONFIRMED,
                            PluggyReconciliationStatus.STRONG,
                        )
                    val canCreateLocal = accountResult.localAccountId == null &&
                        accountResult.status == PluggyReconciliationStatus.UNMATCHED &&
                        accountResult.compatibleCandidateCount == 0
                    val canSelect = canUseExisting || canCreateLocal
                    ReconciliationAccountCard(
                        result = accountResult,
                        selected = accountResult.pluggyAccountExternalId in selectedForImport,
                        onSelectedChange = if (canSelect && !loading) {
                            { checked ->
                                selectedForImport = if (checked) {
                                    selectedForImport + accountResult.pluggyAccountExternalId
                                } else {
                                    selectedForImport - accountResult.pluggyAccountExternalId
                                }
                            }
                        } else null,
                    )
                }

                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text("Importação controlada", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Somente POSTED, sem correspondência local e dos últimos 90 dias. Itens em Revisar, PENDING e créditos de cartão ficam de fora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && selectedForImport.isNotEmpty() && preview != null,
                        onClick = {
                            val remote = preview ?: return@Button
                            loading = true
                            error = null
                            importMessage = null
                            scope.launch {
                                val planResult = runCatching {
                                    withContext(Dispatchers.IO) {
                                        PluggyFirstImportProvisioner.provisionSelectedAccounts(
                                            repository = repository,
                                            remote = remote,
                                            selectedExternalAccountIds = selectedForImport,
                                        )
                                        val effectiveReconciliation = buildReconciliation(repository, remote)
                                        val plan = PluggyControlledImportPlanner.plan(
                                            datasets = remote.accounts.map { accountPreview ->
                                                PluggyAccountDataset(
                                                    account = accountPreview.account,
                                                    transactions = accountPreview.transactions,
                                                    bills = accountPreview.bills,
                                                )
                                            },
                                            reconciliation = effectiveReconciliation,
                                            selectedExternalAccountIds = selectedForImport,
                                            localTransactions = repository.granularTransactions(),
                                        )
                                        effectiveReconciliation to plan
                                    }
                                }
                                planResult.onSuccess { (refreshed, plan) ->
                                    reconciliation = refreshed
                                    pendingPlan = plan
                                }.onFailure { error = it.toSafeMessage() }
                                loading = false
                            }
                        },
                    ) {
                        Text("Preparar importação (${selectedForImport.size} conta(s))")
                    }
                }

                item {
                    Text(
                        "Fase 4 permanece exclusiva do Assistente Financeiro (Teste). Nenhum item marcado como Revisar é importado automaticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun buildReconciliation(
    repository: FinancialRepository,
    remote: PluggySandboxPreview,
): PluggyReconciliationPreview {
    val localAccounts = repository.financialAccounts()
    val localTransactions = repository.granularTransactions()
    val invoicesByAccount = localAccounts
        .filter { it.type == FinancialAccountType.CREDIT_CARD }
        .associate { account -> account.id to repository.creditCardInvoices(account.id) }
    val confirmedLinks = repository.externalAccountLinks(ExternalDataProvider.PLUGGY)
        .associate { it.externalAccountId to it.localAccountId }
    return PluggyReconciliationEngine.reconcile(
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
            confirmedAccountLinks = confirmedLinks,
        ),
    )
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
            KeyValueRow("Vínculo confirmado", result.confirmedAccounts.toString())
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
private fun ReconciliationAccountCard(
    result: PluggyAccountReconciliation,
    selected: Boolean,
    onSelectedChange: ((Boolean) -> Unit)?,
) {
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
                    PluggyReconciliationStatus.CONFIRMED,
                    PluggyReconciliationStatus.STRONG,
                    PluggyReconciliationStatus.PROBABLE -> MaterialTheme.colorScheme.primary
                    PluggyReconciliationStatus.REVIEW -> MaterialTheme.colorScheme.tertiary
                    PluggyReconciliationStatus.UNMATCHED -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            result.localAccountName?.let { KeyValueRow("Conta local", it) }
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
            if (onSelectedChange != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                    Text(
                        when (result.status) {
                            PluggyReconciliationStatus.CONFIRMED ->
                                "Selecionar para sincronizar/importar"
                            PluggyReconciliationStatus.UNMATCHED ->
                                "Criar conta local e selecionar para importar"
                            else -> "Confirmar vínculo e selecionar para importar"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun reconciliationStatusLabel(status: PluggyReconciliationStatus): String = when (status) {
    PluggyReconciliationStatus.CONFIRMED -> "Vínculo confirmado"
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
    else -> "Falha no fluxo Pluggy: ${message ?: this::class.simpleName.orEmpty()}"
}

private fun formatMoney(value: BigDecimal, currencyCode: String): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    runCatching { formatter.currency = Currency.getInstance(currencyCode) }
    return formatter.format(value)
}
