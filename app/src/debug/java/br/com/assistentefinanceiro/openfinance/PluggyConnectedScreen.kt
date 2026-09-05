package br.com.assistentefinanceiro.openfinance

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.data.ExternalAccountLinkRecord
import br.com.assistentefinanceiro.data.ExternalDataProvider
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ConnectedImportPeriodOption(val label: String) {
    LAST_90_DAYS("Últimos 90 dias"),
    LAST_6_MONTHS("Últimos 6 meses"),
    LAST_12_MONTHS("Últimos 12 meses"),
    ALL_AVAILABLE("Todo o período disponível"),
    CUSTOM("Período personalizado"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluggyConnectedScreen(
    repository: FinancialRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val client = remember { PluggyReadOnlyClient() }
    val store = remember { PluggyConnectionStore(context) }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(store.load()) }
    var backendUrl by remember { mutableStateOf(settings.backendUrl) }
    var accessCode by remember { mutableStateOf(settings.accessCode) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PluggySandboxPreview?>(null) }
    var reconciliation by remember { mutableStateOf<PluggyReconciliationPreview?>(null) }
    var selectedForImport by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingPlan by remember { mutableStateOf<PluggyControlledImportPlan?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importPeriod by remember { mutableStateOf(ConnectedImportPeriodOption.LAST_90_DAYS) }
    var customStartDate by remember { mutableStateOf("") }
    var customEndDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var showTechnicalConfig by remember {
        mutableStateOf(settings.backendUrl.isBlank() || settings.accessCode.isBlank())
    }

    val parsedCustomStart = customStartDate.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    val parsedCustomEnd = customEndDate.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    val customPeriodValid = importPeriod != ConnectedImportPeriodOption.CUSTOM ||
        (parsedCustomStart != null && parsedCustomEnd != null &&
            !parsedCustomStart.isAfter(parsedCustomEnd) &&
            !parsedCustomEnd.isAfter(LocalDate.now()))
    val backendConfigured = settings.backendUrl.isNotBlank() && settings.accessCode.isNotBlank()
    val connected = !settings.itemId.isNullOrBlank()

    fun saveTechnicalConfig(): Boolean = runCatching {
        store.saveBackend(backendUrl, accessCode)
        settings = store.load()
        backendUrl = settings.backendUrl
        accessCode = settings.accessCode
        showTechnicalConfig = false
        error = null
        true
    }.getOrElse {
        error = it.safeMessage()
        false
    }

    fun openConnect(updateExisting: Boolean) {
        if (!saveTechnicalConfig()) return
        val current = store.load()
        val builder = Uri.parse(current.backendUrl + "/api/connect").buildUpon()
        if (updateExisting) {
            current.itemId?.let { builder.appendQueryParameter("itemId", it) }
        }
        val base = builder.build().toString()
        val uri = Uri.parse(base + "#accessCode=" + Uri.encode(current.accessCode))
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { error = "Não foi possível abrir o navegador: ${it.message.orEmpty()}" }
    }

    fun loadRemoteData() {
        val current = store.load()
        val item = current.itemId
        if (item.isNullOrBlank()) {
            error = "Conecte uma instituição antes de sincronizar."
            return
        }
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
                    val remote = client.fetchPreview(
                        backendUrl = current.backendUrl,
                        accessCode = current.accessCode,
                        itemId = item,
                    )
                    remote to buildConnectedReconciliation(repository, remote)
                }
            }
            result.onSuccess { (remote, localPreview) ->
                preview = remote
                reconciliation = localPreview
            }.onFailure { throwable ->
                error = throwable.safeMessage()
            }
            loading = false
        }
    }

    pendingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!loading) pendingPlan = null },
            title = { Text("Confirmar importação") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${plan.selectedAccounts} conta(s) selecionada(s)")
                    Text("${plan.importable} transação(ões) serão sincronizadas.")
                    Text("${plan.billDrafts.size} fatura(s) oficial(is) serão sincronizadas.")
                    if (plan.skippedCreditCardPayments > 0) {
                        Text("${plan.skippedCreditCardPayments} crédito(s) serão tratados como pagamento de fatura.")
                    }
                    if (plan.skippedOutsideWindow > 0) {
                        Text("${plan.skippedOutsideWindow} movimentação(ões) ficaram fora do período.")
                    }
                    Text(
                        "Período: " + (plan.windowStartDate?.toString() ?: "início disponível") +
                            " a ${plan.windowEndDate}.",
                    )
                    Text(
                        "PENDING permanece pendente; POSTED permanece realizado. A sincronização é idempotente.",
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
                                    val bills = repository.importExternalBills(plan.billDrafts)
                                    val refreshed = buildConnectedReconciliation(repository, remote)
                                    Triple(imported, bills, refreshed)
                                }
                            }
                            result.onSuccess { (imported, bills, refreshed) ->
                                reconciliation = refreshed
                                selectedForImport = emptySet()
                                importMessage =
                                    "Sincronização concluída: ${imported.imported} nova(s), " +
                                        "${imported.updated} atualizada(s), " +
                                        "${imported.alreadyImported} já existente(s); " +
                                        "${bills.billsSynced} fatura(s) e " +
                                        "${bills.paymentsSynced} pagamento(s)."
                            }.onFailure { throwable -> error = throwable.safeMessage() }
                            loading = false
                        }
                    },
                ) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(enabled = !loading, onClick = { pendingPlan = null }) {
                    Text("Cancelar")
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Open Finance") },
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Conexão Pluggy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "O login da instituição é feito pelo Pluggy Connect. O app não recebe CLIENT_ID, CLIENT_SECRET nem apiKey.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!backendConfigured || showTechnicalConfig) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Configuração inicial", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Informe uma única vez o endereço do backend seguro e o código de pareamento.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = backendUrl,
                                onValueChange = { backendUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("URL HTTPS do backend") },
                                singleLine = true,
                                enabled = !loading,
                            )
                            OutlinedTextField(
                                value = accessCode,
                                onValueChange = { accessCode = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Código de pareamento") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !loading,
                            )
                            Button(
                                onClick = { saveTechnicalConfig() },
                                enabled = !loading && backendUrl.isNotBlank() && accessCode.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Salvar configuração") }
                        }
                    }
                }
            } else {
                item {
                    TextButton(onClick = { showTechnicalConfig = true }, enabled = !loading) {
                        Text("Alterar configuração do backend")
                    }
                }
            }

            if (backendConfigured) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (connected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                if (connected) "Instituição conectada" else "Nenhuma instituição conectada",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (connected) {
                                    "O identificador da conexão fica salvo no aparelho e não precisa mais ser digitado."
                                } else {
                                    "Toque abaixo para escolher a instituição e autorizar o Open Finance."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!connected) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !loading,
                                    onClick = { openConnect(updateExisting = false) },
                                ) { Text("Conectar instituição") }
                            } else {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !loading,
                                    onClick = { loadRemoteData() },
                                ) {
                                    if (loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.height(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text("Sincronizar Open Finance")
                                    }
                                }
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !loading,
                                    onClick = { openConnect(updateExisting = true) },
                                ) { Text("Atualizar autorização/conexão") }
                                TextButton(
                                    enabled = !loading,
                                    onClick = {
                                        store.clearConnection()
                                        settings = store.load()
                                        preview = null
                                        reconciliation = null
                                        selectedForImport = emptySet()
                                    },
                                ) { Text("Trocar instituição") }
                            }
                        }
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
                        Text(message, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            preview?.let { result ->
                item {
                    HorizontalDivider()
                    Text(
                        "Pluggy: ${result.itemStatus}" +
                            result.executionStatus?.let { " • $it" }.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${result.accounts.size} conta(s) retornada(s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(result.accounts) { accountPreview ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(accountPreview.account.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${accountPreview.transactionCount} movimentações • " +
                                    "${accountPreview.pendingCount} pendentes • " +
                                    "${accountPreview.bills.size} faturas",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            reconciliation?.let { result ->
                item {
                    HorizontalDivider()
                    Text("Reconciliação local", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Selecione somente as contas que deseja sincronizar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(accountResult.pluggyAccountName, fontWeight = FontWeight.SemiBold)
                            Text(
                                connectedStatusLabel(accountResult.status),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            accountResult.localAccountName?.let {
                                Text("Conta local: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            if (canSelect) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = accountResult.pluggyAccountExternalId in selectedForImport,
                                        onCheckedChange = { checked ->
                                            selectedForImport = if (checked) {
                                                selectedForImport + accountResult.pluggyAccountExternalId
                                            } else {
                                                selectedForImport - accountResult.pluggyAccountExternalId
                                            }
                                        },
                                    )
                                    Text("Sincronizar esta conta")
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Período para importar", style = MaterialTheme.typography.labelLarge)
                    ConnectedImportPeriodOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = importPeriod == option,
                                onClick = { importPeriod = option },
                                enabled = !loading,
                            )
                            Text(option.label)
                        }
                    }
                    if (importPeriod == ConnectedImportPeriodOption.CUSTOM) {
                        OutlinedTextField(
                            value = customStartDate,
                            onValueChange = { customStartDate = it.take(10) },
                            label = { Text("Data inicial (AAAA-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customEndDate,
                            onValueChange = { customEndDate = it.take(10) },
                            label = { Text("Data final (AAAA-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && selectedForImport.isNotEmpty() && customPeriodValid,
                        onClick = {
                            val remote = preview ?: return@Button
                            val today = LocalDate.now()
                            val params = when (importPeriod) {
                                ConnectedImportPeriodOption.LAST_90_DAYS -> Triple(90L, null, today)
                                ConnectedImportPeriodOption.LAST_6_MONTHS ->
                                    Triple(null, today.minusMonths(6), today)
                                ConnectedImportPeriodOption.LAST_12_MONTHS ->
                                    Triple(null, today.minusMonths(12), today)
                                ConnectedImportPeriodOption.ALL_AVAILABLE -> Triple(null, null, today)
                                ConnectedImportPeriodOption.CUSTOM ->
                                    Triple(null, checkNotNull(parsedCustomStart), checkNotNull(parsedCustomEnd))
                            }
                            loading = true
                            error = null
                            scope.launch {
                                val planResult = runCatching {
                                    withContext(Dispatchers.IO) {
                                        PluggyFirstImportProvisioner.provisionSelectedAccounts(
                                            repository = repository,
                                            remote = remote,
                                            selectedExternalAccountIds = selectedForImport,
                                            today = today,
                                            lookbackDays = params.first,
                                            startDate = params.second,
                                            endDate = params.third,
                                        )
                                        val refreshed = buildConnectedReconciliation(repository, remote)
                                        val plan = PluggyControlledImportPlanner.plan(
                                            datasets = remote.accounts.map { accountPreview ->
                                                PluggyAccountDataset(
                                                    account = accountPreview.account,
                                                    transactions = accountPreview.transactions,
                                                    bills = accountPreview.bills,
                                                )
                                            },
                                            reconciliation = refreshed,
                                            selectedExternalAccountIds = selectedForImport,
                                            localTransactions = repository.granularTransactions(),
                                            today = today,
                                            lookbackDays = params.first,
                                            startDate = params.second,
                                            endDate = params.third,
                                        )
                                        refreshed to plan
                                    }
                                }
                                planResult.onSuccess { (refreshed, plan) ->
                                    reconciliation = refreshed
                                    pendingPlan = plan
                                }.onFailure { error = it.safeMessage() }
                                loading = false
                            }
                        },
                    ) { Text("Preparar importação") }
                }
            }
        }
    }
}

private fun buildConnectedReconciliation(
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

private fun connectedStatusLabel(status: PluggyReconciliationStatus): String = when (status) {
    PluggyReconciliationStatus.CONFIRMED -> "Vínculo confirmado"
    PluggyReconciliationStatus.STRONG -> "Vínculo forte"
    PluggyReconciliationStatus.PROBABLE -> "Vínculo provável"
    PluggyReconciliationStatus.REVIEW -> "Revisar"
    PluggyReconciliationStatus.UNMATCHED -> "Sem correspondência"
}

private fun Throwable.safeMessage(): String = when (this) {
    is PluggyApiException -> buildString {
        append("HTTP ").append(httpStatus)
        codeDescription?.let { append(" • ").append(it) }
        append(": ").append(message)
    }
    is IllegalArgumentException -> message ?: "Parâmetro inválido"
    else -> "Falha no fluxo Pluggy: ${message ?: this::class.simpleName.orEmpty()}"
}
