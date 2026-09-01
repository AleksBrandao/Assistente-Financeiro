package br.com.assistentefinanceiro

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.notifications.*
import br.com.assistentefinanceiro.importing.MobillsImportAnalyzer
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.importing.SimpleXlsxReader
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppScreen {
    STATEMENT,
    DIAGNOSTIC,
    ACCOUNTS,
    SEARCH,
    SUMMARY,
    PLANNING,
}

private data class AccountLedgerItem(
    val key: String,
    val occurredAt: LocalDateTime,
    val direction: FinancialTransactionDirection,
    val amount: java.math.BigDecimal,
    val description: String,
    val detail: String,
    val transaction: FinancialTransactionRecord? = null,
    val movement: AccountMovementRecord? = null,
)

private data class StatementInvoiceItem(
    val account: FinancialAccountRecord,
    val invoice: CreditCardInvoiceRecord,
    val transaction: FinancialTransactionRecord,
)

private data class PlanningItem(
    val transaction: FinancialTransactionRecord,
    val date: LocalDate,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0A7D65))) {
                val store = remember { DiagnosticStore(applicationContext) }
                val preferences = remember { BankPackagePreferences(applicationContext) }
                var screen by remember { mutableStateOf(AppScreen.STATEMENT) }

                when (screen) {
                    AppScreen.STATEMENT -> MonthlyStatementScreen(
                        store = store,
                        onOpenAccounts = { screen = AppScreen.ACCOUNTS },
                        onOpenSearch = { screen = AppScreen.SEARCH },
                        onOpenSummary = { screen = AppScreen.SUMMARY },
                        onOpenPlanning = { screen = AppScreen.PLANNING },
                    )
                    AppScreen.DIAGNOSTIC -> DiagnosticScreen(
                        store = store,
                        preferences = preferences,
                        onOpenStatement = { screen = AppScreen.STATEMENT },
                    )
                    AppScreen.ACCOUNTS -> AccountsScreen(
                        store = store,
                        onOpenStatement = { screen = AppScreen.STATEMENT },
                        onOpenDiagnostic = { screen = AppScreen.DIAGNOSTIC },
                    )
                    AppScreen.SEARCH -> TransactionSearchScreen(
                        store = store,
                        onBack = { screen = AppScreen.STATEMENT },
                    )
                    AppScreen.SUMMARY -> AnnualSummaryScreen(
                        store = store,
                        onBack = { screen = AppScreen.STATEMENT },
                    )
                    AppScreen.PLANNING -> PlanningScreen(
                        store = store,
                        onBack = { screen = AppScreen.STATEMENT },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MonthlyStatementScreen(
        store: DiagnosticStore,
        onOpenAccounts: () -> Unit,
        onOpenSearch: () -> Unit,
        onOpenSummary: () -> Unit,
        onOpenPlanning: () -> Unit,
    ) {
        var refresh by remember { mutableIntStateOf(0) }
        var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
        var pendingOnly by remember { mutableStateOf(false) }
        val transactions = remember(refresh) { store.recentTransactions(limit = 10_000) }
        val creditCardAccounts = remember(refresh) {
            store.financialAccounts()
                .filter { it.type == FinancialAccountType.CREDIT_CARD }
        }
        val invoicesByAccount = remember(creditCardAccounts, refresh) {
            creditCardAccounts.flatMap { account ->
                store.creditCardInvoices(account.id).map { account to it }
            }
        }
        val statementInvoiceItems = remember(invoicesByAccount) {
            invoicesByAccount.mapNotNull { (account, invoice) ->
                        val dueDate = invoice.dueDate ?: return@mapNotNull null
                        if (invoice.total.signum() == 0) return@mapNotNull null
                        val isCredit = invoice.total.signum() < 0
                        StatementInvoiceItem(
                            account = account,
                            invoice = invoice,
                            transaction = FinancialTransactionRecord(
                                id = -invoice.id,
                                sourceEventId = null,
                                direction = if (isCredit) {
                                    FinancialTransactionDirection.INCOME
                                } else FinancialTransactionDirection.EXPENSE,
                                type = if (isCredit) {
                                    FinancialTransactionType.IMPORTED_INCOME
                                } else FinancialTransactionType.IMPORTED_EXPENSE,
                                amount = invoice.total.abs().toPlainString(),
                                occurredAt = dueDate.atTime(23, 59, 59).toString(),
                                description = "Fatura ${account.name}",
                                sourcePackage = "credit-card-invoice",
                                status = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                                    TransactionStatus.REALIZED
                                } else TransactionStatus.PENDING,
                                account = account.name,
                                accountId = account.id,
                                invoiceId = invoice.id,
                            ),
                        )
                    }
        }
        val invoiceItemByTransactionId = remember(statementInvoiceItems) {
            statementInvoiceItems.associateBy { it.transaction.id }
        }
        val creditCardAccountIds = remember(creditCardAccounts) {
            creditCardAccounts.map { it.id }.toSet()
        }
        val consolidatedInvoiceIds = remember(statementInvoiceItems) {
            statementInvoiceItems.map { it.invoice.id }.toSet()
        }
        val unconsolidatedCardTransactionCount = remember(
            transactions, creditCardAccountIds, consolidatedInvoiceIds,
        ) {
            transactions.count { transaction ->
                val isCardPurchase = transaction.type == FinancialTransactionType.CARD_PURCHASE ||
                    (transaction.accountId != null &&
                        transaction.accountId in creditCardAccountIds)
                isCardPurchase && (
                    transaction.invoiceId == null ||
                        transaction.invoiceId !in consolidatedInvoiceIds
                    )
            }
        }
        val statementTransactions = remember(
            transactions, statementInvoiceItems, creditCardAccountIds,
        ) {
            transactions.filterNot { transaction ->
                transaction.type == FinancialTransactionType.CARD_PURCHASE ||
                    (transaction.accountId != null &&
                        transaction.accountId in creditCardAccountIds)
            } + statementInvoiceItems.map { it.transaction }
        }
        val statement = remember(statementTransactions, selectedMonth) {
            MonthlyStatementCalculator.calculate(selectedMonth, statementTransactions)
        }
        val generalProjectedBalance = remember(refresh, selectedMonth) {
            store.generalProjectedBalance(selectedMonth.atEndOfMonth())
        }
        val visibleGroups = remember(statement.groups, pendingOnly) {
            if (!pendingOnly) statement.groups else statement.groups.mapNotNull { group ->
                group.copy(
                    transactions = group.transactions.filter {
                        it.status == TransactionStatus.PENDING
                    },
                ).takeIf { it.transactions.isNotEmpty() }
            }
        }
        var editingTransaction by remember {
            mutableStateOf<FinancialTransactionRecord?>(null)
        }
        var deletingStatementTransaction by remember {
            mutableStateOf<FinancialTransactionRecord?>(null)
        }
        var deletingStatementScope by remember {
            mutableStateOf(TransactionSeriesScope.ONLY_THIS)
        }
        var importPreview by remember { mutableStateOf<MobillsImportPreview?>(null) }
        var includePossibleDuplicates by remember { mutableStateOf(false) }
        var importMessage by remember { mutableStateOf<String?>(null) }
        var importError by remember { mutableStateOf<String?>(null) }
        var readingImport by remember { mutableStateOf(false) }
        var selectedStatementInvoice by remember {
            mutableStateOf<StatementInvoiceItem?>(null)
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                readingImport = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri).use { input ->
                                requireNotNull(input) { "Não foi possível abrir o arquivo" }
                                store.markExistingTransactions(
                                    MobillsImportAnalyzer.analyze(
                                        rawRows = SimpleXlsxReader.readMobillsRows(input),
                                    )
                                )
                            }
                        }
                    }.onSuccess {
                        importPreview = it
                        includePossibleDuplicates = false
                    }.onFailure {
                        importError = it.message ?: "Arquivo Mobills inválido"
                    }
                    readingImport = false
                }
            }
        }

        selectedStatementInvoice?.let { selected ->
            InvoiceDetailScreen(
                store = store,
                account = selected.account,
                invoice = selected.invoice,
                onBack = { selectedStatementInvoice = null },
                onPayment = { amount, paidAt, sourceAccountId ->
                    if (store.recordInvoicePayment(
                            selected.invoice, amount, paidAt, sourceAccountId,
                        )) {
                        selectedStatementInvoice = null
                        refresh++
                        true
                    } else false
                },
                onDeletePayment = { paymentId ->
                    if (store.deleteInvoicePayment(selected.invoice, paymentId)) {
                        selectedStatementInvoice = null
                        refresh++
                        true
                    } else false
                },
                onInvoiceAdjustment = { officialTotal ->
                    if (store.adjustInvoiceTotal(selected.invoice, officialTotal)) {
                        selectedStatementInvoice = null
                        refresh++
                        true
                    } else false
                },
                onTransactionChanged = {
                    selectedStatementInvoice = null
                    refresh++
                },
            )
            return
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Assistente Financeiro") },
                    actions = {
                        TextButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    )
                                )
                            },
                            enabled = !readingImport,
                        ) {
                            Text(if (readingImport) "Lendo…" else "Importar")
                        }
                        TextButton(onClick = onOpenAccounts) {
                            Text("Contas")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MonthSelector(
                        selectedMonth = selectedMonth,
                        onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                        onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onOpenSearch, modifier = Modifier.weight(1f)) {
                            Text("Pesquisar")
                        }
                        OutlinedButton(onClick = onOpenSummary, modifier = Modifier.weight(1f)) {
                            Text("Resumo anual")
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onOpenPlanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Planejamento · próximos 90 dias")
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = pendingOnly,
                            onCheckedChange = { pendingOnly = it },
                        )
                        Text("Mostrar somente pendentes")
                    }
                }
                item {
                    StatementSummary(
                        statement = statement,
                        generalProjectedBalance = generalProjectedBalance,
                    )
                }
                if (unconsolidatedCardTransactionCount > 0) {
                    item {
                        Card {
                            Text(
                                "$unconsolidatedCardTransactionCount compras de cartão ainda não " +
                                    "foram vinculadas a uma fatura com vencimento. " +
                                    "Revise o cadastro do cartão em Contas.",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { refresh++ },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Atualizar movimentações")
                    }
                }
                if (visibleGroups.isEmpty()) {
                    item {
                        Card {
                            Text(
                                text = if (pendingOnly) {
                                    "Nenhuma movimentação pendente neste mês."
                                } else {
                                    "Nenhuma movimentação reconhecida neste mês."
                                },
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                visibleGroups.forEach { group ->
                    item(key = "date-${group.date}") {
                        Text(
                            text = formatDate(group.date),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(
                        items = group.transactions,
                        key = { "statement-${it.id}" },
                    ) { transaction ->
                        val invoiceItem = invoiceItemByTransactionId[transaction.id]
                        if (invoiceItem != null) {
                            StatementInvoiceCard(
                                item = invoiceItem,
                                onClick = { selectedStatementInvoice = invoiceItem },
                            )
                        } else {
                            TransactionCard(
                                transaction = transaction,
                                onClick = { editingTransaction = transaction },
                            )
                        }
                    }
                }
            }
        }

        editingTransaction?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                onDismiss = { editingTransaction = null },
                onSave = { description, category, status, amount, dueDate, plannedDate, paidAt, applyToFuture, scope ->
                    if (
                        store.updateTransactionDetails(
                            transactionId = transaction.id,
                            description = description,
                            category = category,
                            status = status,
                            amount = amount,
                            dueDate = dueDate,
                            plannedPaymentDate = plannedDate,
                            paidAt = paidAt,
                            applyToFuture = applyToFuture,
                            seriesScope = scope,
                        )
                    ) {
                        editingTransaction = null
                        refresh++
                    }
                },
                onDelete = if (transaction.origin == TransactionOrigin.MANUAL) {
                    { selectedScope ->
                        editingTransaction = null
                        deletingStatementTransaction = transaction
                        deletingStatementScope = selectedScope
                    }
                } else null,
            )
        }

        deletingStatementTransaction?.let { transaction ->
            val scopeDescription = when (deletingStatementScope) {
                TransactionSeriesScope.ONLY_THIS -> "somente esta movimentação"
                TransactionSeriesScope.THIS_AND_FUTURE -> "esta e as próximas movimentações"
                TransactionSeriesScope.ALL -> "todas as movimentações da série"
            }
            AlertDialog(
                onDismissRequest = { deletingStatementTransaction = null },
                title = { Text("Excluir movimentação?") },
                text = { Text("Será excluída $scopeDescription: ${transaction.description}.") },
                confirmButton = {
                    TextButton(onClick = {
                        if (store.deleteManualTransaction(transaction.id, deletingStatementScope)) {
                            deletingStatementTransaction = null
                            refresh++
                        }
                    }) {
                        Text("Excluir", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletingStatementTransaction = null }) {
                        Text("Cancelar")
                    }
                },
            )
        }

        importPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = { importPreview = null },
                title = { Text("Prévia da importação Mobills") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Realizados: ${preview.readyCount}")
                        Text("Pendentes: ${preview.pendingCount}")
                        Text("Possíveis duplicidades: ${preview.possibleDuplicateCount}")
                        Text("Rejeitados: ${preview.rejectedCount}")
                        preview.rejectionReasons.forEach { (reason, count) ->
                            Text(
                                "• $reason: $count",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (preview.possibleDuplicateCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = includePossibleDuplicates,
                                    onCheckedChange = { includePossibleDuplicates = it },
                                )
                                Text("Importar também as possíveis duplicidades")
                            }
                        }
                        Text(
                            "Registros rejeitados e duplicidades não selecionadas não serão gravados.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val result = store.importMobills(preview, includePossibleDuplicates)
                        importPreview = null
                        refresh++
                        importMessage = "${result.imported} movimentações importadas" +
                            if (result.alreadyImported > 0) {
                                " · ${result.alreadyImported} já existentes"
                            } else ""
                    }) { Text("Confirmar importação") }
                },
                dismissButton = {
                    TextButton(onClick = { importPreview = null }) { Text("Cancelar") }
                },
            )
        }

        importMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { importMessage = null },
                title = { Text("Importação concluída") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { importMessage = null }) { Text("OK") }
                },
            )
        }

        importError?.let { message ->
            AlertDialog(
                onDismissRequest = { importError = null },
                title = { Text("Não foi possível importar") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { importError = null }) { Text("OK") }
                },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AccountsScreen(
        store: DiagnosticStore,
        onOpenStatement: () -> Unit,
        onOpenDiagnostic: () -> Unit,
    ) {
        var refresh by remember { mutableIntStateOf(0) }
        val accounts = remember(refresh) { store.financialAccounts() }
        val bankBalances = remember(accounts, refresh) {
            accounts.filter { it.type == FinancialAccountType.BANK_ACCOUNT }
                .associateWith(store::accountBalance)
        }
        var editingAccount by remember { mutableStateOf<FinancialAccountRecord?>(null) }
        var creatingAccount by remember { mutableStateOf(false) }
        var viewingInvoicesFor by remember { mutableStateOf<FinancialAccountRecord?>(null) }
        var viewingMovementsFor by remember { mutableStateOf<FinancialAccountRecord?>(null) }
        var creatingTransfer by remember { mutableStateOf(false) }

        viewingInvoicesFor?.let { account ->
            CardInvoicesScreen(
                store = store,
                account = account,
                onBack = {
                    viewingInvoicesFor = null
                    refresh++
                },
            )
            return
        }
        viewingMovementsFor?.let { account ->
            AccountMovementsScreen(
                store = store,
                account = account,
                onBack = {
                    viewingMovementsFor = null
                    refresh++
                },
                onChanged = { refresh++ },
            )
            return
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Contas e cartões") },
                    actions = {
                        TextButton(onClick = onOpenStatement) { Text("Extrato") }
                        TextButton(onClick = onOpenDiagnostic) { Text("Diagnóstico") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (bankBalances.isNotEmpty()) {
                    item {
                        val realized = bankBalances.values.fold(java.math.BigDecimal.ZERO) {
                                total, balance -> total + balance.realizedBalance
                        }
                        val projected = bankBalances.values.fold(java.math.BigDecimal.ZERO) {
                                total, balance -> total + balance.projectedBalance
                        }
                        Card {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Saldo consolidado", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    formatCurrency(realized.toPlainString()),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = if (realized.signum() < 0) Color(0xFFBA3B46)
                                    else Color(0xFF0A7D65),
                                )
                                Text("Previsto: ${formatCurrency(projected.toPlainString())}")
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { creatingAccount = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Adicionar conta ou cartão") }
                }
                if (accounts.count { it.type == FinancialAccountType.BANK_ACCOUNT } >= 2) {
                    item {
                        OutlinedButton(
                            onClick = { creatingTransfer = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Transferir entre contas") }
                    }
                }
                items(accounts, key = { "account-${it.id}" }) { account ->
                    val balance = bankBalances[account]
                    Card {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium)
                                if (account.isDefault) {
                                    Text("Padrão", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                account.type.displayName,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (account.type == FinancialAccountType.CREDIT_CARD) {
                                Text(
                                    "Fechamento: ${account.closingDay ?: "não informado"} · " +
                                        "Vencimento: ${account.dueDay ?: "não informado"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (balance != null) {
                                Text(
                                    "Saldo atual: ${formatCurrency(balance.realizedBalance.toPlainString())}",
                                    color = if (balance.realizedBalance.signum() < 0) {
                                        Color(0xFFBA3B46)
                                    } else Color(0xFF0A7D65),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (balance.projectedBalance != balance.realizedBalance) {
                                    Text(
                                        "Previsto: ${formatCurrency(balance.projectedBalance.toPlainString())}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { editingAccount = account }) {
                                    Text("Editar")
                                }
                                if (account.type == FinancialAccountType.CREDIT_CARD) {
                                    TextButton(onClick = { viewingInvoicesFor = account }) {
                                        Text("Faturas")
                                    }
                                } else {
                                    TextButton(onClick = { viewingMovementsFor = account }) {
                                        Text("Movimentações")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val dialogAccount = editingAccount ?: if (creatingAccount) {
            FinancialAccountRecord(
                id = 0,
                name = "",
                type = FinancialAccountType.BANK_ACCOUNT,
            )
        } else null
        dialogAccount?.let { account ->
            EditAccountDialog(
                account = account,
                isNew = creatingAccount,
                onDismiss = {
                    editingAccount = null
                    creatingAccount = false
                },
                onSave = { name, type, closingDay, dueDay, isDefault, cardIdentifiers,
                           openingBalance, openingBalanceDate ->
                    if (
                        store.saveFinancialAccount(
                            id = account.id.takeUnless { creatingAccount },
                            name = name,
                            type = type,
                            closingDay = closingDay,
                            dueDay = dueDay,
                            isDefault = isDefault,
                            cardIdentifiers = cardIdentifiers,
                            openingBalance = openingBalance,
                            openingBalanceDate = openingBalanceDate,
                        )
                    ) {
                        editingAccount = null
                        creatingAccount = false
                        refresh++
                    }
                },
            )
        }
        if (creatingTransfer) {
            TransferDialog(
                accounts = accounts.filter { it.type == FinancialAccountType.BANK_ACCOUNT },
                onDismiss = { creatingTransfer = false },
                onSave = { source, destination, amount, date, description ->
                    if (store.recordTransfer(source, destination, amount, date, description)) {
                        creatingTransfer = false
                        refresh++
                    }
                },
            )
        }
    }

    @Composable
    private fun TransferDialog(
        accounts: List<FinancialAccountRecord>,
        onDismiss: () -> Unit,
        onSave: (Long, Long, java.math.BigDecimal, LocalDate, String) -> Unit,
    ) {
        var sourceId by remember { mutableStateOf(accounts.first().id) }
        var destinationId by remember { mutableStateOf(accounts[1].id) }
        var amount by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(LocalDate.now().toString()) }
        var description by remember { mutableStateOf("Transferência entre contas") }
        var sourceExpanded by remember { mutableStateOf(false) }
        var destinationExpanded by remember { mutableStateOf(false) }
        val amountValue = if (',' in amount) {
            amount.replace(".", "").replace(',', '.').toBigDecimalOrNull()
        } else amount.toBigDecimalOrNull()
        val dateValue = runCatching { LocalDate.parse(date) }.getOrNull()
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Transferir entre contas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        OutlinedButton(
                            onClick = { sourceExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("De: ${accounts.first { it.id == sourceId }.name}") }
                        DropdownMenu(sourceExpanded, { sourceExpanded = false }) {
                            accounts.filter { it.id != destinationId }.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = { sourceId = account.id; sourceExpanded = false },
                                )
                            }
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { destinationExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Para: ${accounts.first { it.id == destinationId }.name}") }
                        DropdownMenu(destinationExpanded, { destinationExpanded = false }) {
                            accounts.filter { it.id != sourceId }.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = {
                                        destinationId = account.id
                                        destinationExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter { c -> c.isDigit() || c in ",." } },
                        label = { Text("Valor") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    DatePickerField(date, "Data", onValueChange = { date = it })
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(80) },
                        label = { Text("Descrição") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(sourceId, destinationId, checkNotNull(amountValue),
                            checkNotNull(dateValue), description)
                    },
                    enabled = sourceId != destinationId && amountValue?.signum() == 1 &&
                        dateValue != null,
                ) { Text("Transferir") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AccountMovementsScreen(
        store: DiagnosticStore,
        account: FinancialAccountRecord,
        onBack: () -> Unit,
        onChanged: () -> Unit,
    ) {
        var refresh by remember(account.id) { mutableIntStateOf(0) }
        var selectedMonth by remember(account.id) { mutableStateOf(YearMonth.now()) }
        val movements = remember(account.id, refresh) { store.accountMovements(account.id) }
        val transactions = remember(account.id, refresh) {
            store.recentTransactions(10_000).filter { it.accountId == account.id }
        }
        val balance = remember(account.id, refresh) { store.accountBalance(account) }
        val ledgerItems = remember(transactions, movements) {
            val transactionItems = transactions.mapNotNull { transaction ->
                val occurredAt = runCatching {
                    LocalDateTime.parse(transaction.occurredAt)
                }.getOrNull() ?: return@mapNotNull null
                AccountLedgerItem(
                    key = "transaction-${transaction.id}",
                    occurredAt = occurredAt,
                    direction = transaction.direction,
                    amount = transaction.amount.toBigDecimalOrNull() ?: return@mapNotNull null,
                    description = transaction.description,
                    detail = listOf(
                        transaction.category.displayName,
                        if (transaction.status == TransactionStatus.PENDING) "Pendente"
                        else "Realizada",
                    ).joinToString(" · "),
                    transaction = transaction,
                )
            }
            val movementItems = movements.map { movement ->
                AccountLedgerItem(
                    key = "movement-${movement.id}",
                    // Movimentações criadas no app aparecem antes das importadas no mesmo dia.
                    occurredAt = movement.occurredAt.atTime(23, 59, 59),
                    direction = if (movement.direction == AccountMovementDirection.CREDIT) {
                        FinancialTransactionDirection.INCOME
                    } else FinancialTransactionDirection.EXPENSE,
                    amount = movement.amount,
                    description = movement.description,
                    detail = listOfNotNull(
                        movement.relatedAccountName,
                        if (movement.type == AccountMovementType.TRANSFER) "Transferência"
                        else "Pagamento de fatura",
                    ).joinToString(" · "),
                    movement = movement,
                )
            }
            (transactionItems + movementItems).sortedWith(
                compareByDescending<AccountLedgerItem> { it.occurredAt }
                    .thenByDescending { it.transaction?.id ?: it.movement?.id ?: 0L },
            )
        }
        val visibleLedgerItems = remember(ledgerItems, selectedMonth) {
            ledgerItems.filter { YearMonth.from(it.occurredAt) == selectedMonth }
        }
        var addingTransaction by remember { mutableStateOf(false) }
        var editingTransaction by remember { mutableStateOf<FinancialTransactionRecord?>(null) }
        var deletingTransaction by remember { mutableStateOf<FinancialTransactionRecord?>(null) }
        var deletingSeriesScope by remember { mutableStateOf(TransactionSeriesScope.ONLY_THIS) }
        var deletingTransfer by remember { mutableStateOf<AccountMovementRecord?>(null) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(account.name) },
                    actions = { TextButton(onClick = onBack) { Text("Contas") } },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("Movimentações da conta", style = MaterialTheme.typography.headlineSmall) }
                item {
                    Card {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Saldo atual", style = MaterialTheme.typography.labelLarge)
                            Text(
                                formatCurrency(balance.realizedBalance.toPlainString()),
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (balance.realizedBalance.signum() < 0) {
                                    Color(0xFFBA3B46)
                                } else Color(0xFF0A7D65),
                            )
                            Text(
                                "Saldo previsto: " +
                                    formatCurrency(balance.projectedBalance.toPlainString()),
                            )
                            account.openingBalanceDate?.let { date ->
                                Text(
                                    "Saldo inicial em ${date.format(SHORT_DATE_FORMATTER)}: " +
                                        formatCurrency(account.openingBalance.toPlainString()),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { addingTransaction = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Adicionar receita ou despesa") }
                }
                item {
                    MonthSelector(
                        selectedMonth = selectedMonth,
                        onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                        onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                    )
                }
                if (visibleLedgerItems.isEmpty()) {
                    item {
                        Card {
                            Text(
                                "Nenhuma movimentação nesta conta em ${formatMonth(selectedMonth)}.",
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                            )
                        }
                    }
                }
                items(visibleLedgerItems, key = { it.key }) { ledgerItem ->
                    Card(onClick = {
                        ledgerItem.transaction?.takeIf {
                            it.origin == TransactionOrigin.MANUAL
                        }?.let { editingTransaction = it }
                    }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ledgerItem.description, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    ledgerItem.occurredAt.toLocalDate().format(SHORT_DATE_FORMATTER) +
                                        (ledgerItem.detail.takeIf { it.isNotBlank() }
                                            ?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                (if (ledgerItem.direction == FinancialTransactionDirection.EXPENSE) {
                                    "- "
                                } else "+ ") + formatCurrency(ledgerItem.amount.toPlainString()),
                                color = if (ledgerItem.direction == FinancialTransactionDirection.EXPENSE) {
                                    Color(0xFFBA3B46)
                                } else Color(0xFF0A7D65),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (ledgerItem.movement?.type == AccountMovementType.TRANSFER) {
                                TextButton(onClick = { deletingTransfer = ledgerItem.movement }) {
                                    Text("Excluir", color = Color(0xFFBA3B46))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (addingTransaction) {
            ManualTransactionDialog(
                onDismiss = { addingTransaction = false },
                onSave = { direction, amount, date, description, status, occurrences ->
                    if (
                        store.recordManualTransaction(
                            account.id, direction, amount, date, description, status, occurrences,
                        )
                    ) {
                        addingTransaction = false
                        refresh++
                        onChanged()
                    }
                },
            )
        }
        editingTransaction?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                onDismiss = { editingTransaction = null },
                onSave = { description, category, status, amount, dueDate, plannedDate, paidAt, applyToFuture, scope ->
                    if (
                        store.updateTransactionDetails(
                            transaction.id, description, category, status,
                            amount, dueDate, plannedDate, paidAt, applyToFuture, scope,
                        )
                    ) {
                        editingTransaction = null
                        refresh++
                        onChanged()
                    }
                },
                onDelete = { scope ->
                    editingTransaction = null
                    deletingTransaction = transaction
                    deletingSeriesScope = scope
                },
            )
        }
        deletingTransaction?.let { transaction ->
            AlertDialog(
                onDismissRequest = { deletingTransaction = null },
                title = { Text("Excluir movimentação manual?") },
                text = { Text(transaction.description) },
                confirmButton = {
                    TextButton(onClick = {
                        if (store.deleteManualTransaction(transaction.id, deletingSeriesScope)) {
                            deletingTransaction = null
                            refresh++
                            onChanged()
                        }
                    }) { Text("Excluir", color = Color(0xFFBA3B46)) }
                },
                dismissButton = {
                    TextButton(onClick = { deletingTransaction = null }) { Text("Cancelar") }
                },
            )
        }
        deletingTransfer?.let { movement ->
            AlertDialog(
                onDismissRequest = { deletingTransfer = null },
                title = { Text("Excluir transferência?") },
                text = { Text("Os lançamentos nas duas contas serão removidos.") },
                confirmButton = {
                    TextButton(onClick = {
                        if (store.deleteTransfer(movement.id)) {
                            deletingTransfer = null
                            refresh++
                            onChanged()
                        }
                    }) { Text("Excluir", color = Color(0xFFBA3B46)) }
                },
                dismissButton = {
                    TextButton(onClick = { deletingTransfer = null }) { Text("Cancelar") }
                },
            )
        }
    }

    @Composable
    private fun ManualTransactionDialog(
        onDismiss: () -> Unit,
        onSave: (
            FinancialTransactionDirection, java.math.BigDecimal, LocalDate, String,
            TransactionStatus, Int,
        ) -> Unit,
    ) {
        var direction by remember { mutableStateOf(FinancialTransactionDirection.EXPENSE) }
        var amount by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(LocalDate.now().toString()) }
        var description by remember { mutableStateOf("") }
        var status by remember { mutableStateOf(TransactionStatus.REALIZED) }
        var occurrences by remember { mutableStateOf("1") }
        val amountValue = if (',' in amount) {
            amount.replace(".", "").replace(',', '.').toBigDecimalOrNull()
        } else amount.toBigDecimalOrNull()
        val dateValue = runCatching { LocalDate.parse(date) }.getOrNull()
        val occurrencesValue = occurrences.toIntOrNull()
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nova movimentação") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = direction == FinancialTransactionDirection.EXPENSE,
                            onClick = { direction = FinancialTransactionDirection.EXPENSE },
                        )
                        Text("Despesa")
                        RadioButton(
                            selected = direction == FinancialTransactionDirection.INCOME,
                            onClick = { direction = FinancialTransactionDirection.INCOME },
                        )
                        Text("Receita")
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter { c -> c.isDigit() || c in ",." } },
                        label = { Text("Valor") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    DatePickerField(date, "Data", onValueChange = { date = it })
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(100) },
                        label = { Text("Descrição") },
                    )
                    OutlinedTextField(
                        value = occurrences,
                        onValueChange = { occurrences = it.filter(Char::isDigit).take(3) },
                        label = { Text("Repetições mensais") },
                        supportingText = { Text("1 para lançamento único; máximo 120") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = status == TransactionStatus.REALIZED,
                            onCheckedChange = {
                                status = if (it) TransactionStatus.REALIZED
                                else TransactionStatus.PENDING
                            },
                        )
                        Text(if (status == TransactionStatus.REALIZED) "Realizada" else "Pendente")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(
                            direction, checkNotNull(amountValue), checkNotNull(dateValue),
                            description, status, checkNotNull(occurrencesValue),
                        )
                    },
                    enabled = amountValue?.signum() == 1 && dateValue != null &&
                        description.isNotBlank() && occurrencesValue != null &&
                        occurrencesValue in 1..120,
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        )
    }

    @Composable
    private fun EditAccountDialog(
        account: FinancialAccountRecord,
        isNew: Boolean,
        onDismiss: () -> Unit,
        onSave: (
            String, FinancialAccountType, Int?, Int?, Boolean, String?,
            java.math.BigDecimal, LocalDate?,
        ) -> Unit,
    ) {
        var name by remember(account.id, isNew) { mutableStateOf(account.name) }
        var type by remember(account.id, isNew) { mutableStateOf(account.type) }
        var closingDay by remember(account.id, isNew) {
            mutableStateOf(account.closingDay?.toString().orEmpty())
        }
        var dueDay by remember(account.id, isNew) {
            mutableStateOf(account.dueDay?.toString().orEmpty())
        }
        var isDefault by remember(account.id, isNew) { mutableStateOf(account.isDefault) }
        var cardIdentifiers by remember(account.id, isNew) {
            mutableStateOf(account.cardIdentifiers.orEmpty())
        }
        var openingBalance by remember(account.id, isNew) {
            mutableStateOf(account.openingBalance.toPlainString().replace('.', ','))
        }
        var openingBalanceDate by remember(account.id, isNew) {
            mutableStateOf(account.openingBalanceDate?.toString() ?: LocalDate.now().toString())
        }
        var typeMenuExpanded by remember { mutableStateOf(false) }
        val closingValue = closingDay.toIntOrNull()
        val dueValue = dueDay.toIntOrNull()
        val daysValid = (closingDay.isBlank() || closingValue in 1..31) &&
            (dueDay.isBlank() || dueValue in 1..31)
        val openingBalanceValue = if (',' in openingBalance) {
            openingBalance.replace(".", "").replace(',', '.').toBigDecimalOrNull()
        } else openingBalance.toBigDecimalOrNull()
        val openingBalanceDateValue = runCatching { LocalDate.parse(openingBalanceDate) }.getOrNull()

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isNew) "Nova conta ou cartão" else "Editar conta ou cartão") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text("Nome") },
                        singleLine = true,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { typeMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(type.displayName) }
                        DropdownMenu(
                            expanded = typeMenuExpanded,
                            onDismissRequest = { typeMenuExpanded = false },
                        ) {
                            FinancialAccountType.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.displayName) },
                                    onClick = {
                                        type = option
                                        if (type == FinancialAccountType.BANK_ACCOUNT) {
                                            closingDay = ""
                                            dueDay = ""
                                            isDefault = false
                                            cardIdentifiers = ""
                                        }
                                        typeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (type == FinancialAccountType.CREDIT_CARD) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = closingDay,
                                onValueChange = { closingDay = it.filter(Char::isDigit).take(2) },
                                label = { Text("Fechamento") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = dueDay,
                                onValueChange = { dueDay = it.filter(Char::isDigit).take(2) },
                                label = { Text("Vencimento") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                            Text("Cartão padrão")
                        }
                        OutlinedTextField(
                            value = cardIdentifiers,
                            onValueChange = {
                                cardIdentifiers = it.filter { char ->
                                    char.isDigit() || char in ",/; "
                                }.take(40)
                            },
                            label = { Text("Finais do cartão") },
                            supportingText = { Text("Ex.: 6426, 5253") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = openingBalance,
                            onValueChange = { value ->
                                openingBalance = value.filter {
                                    it.isDigit() || it in ",.-"
                                }.take(18)
                            },
                            label = { Text("Saldo inicial") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        DatePickerField(
                            openingBalanceDate,
                            "Data do saldo",
                            onValueChange = { openingBalanceDate = it },
                        )
                        Text(
                            "Este saldo será o fechamento do dia; movimentações posteriores " +
                                "alterarão o valor",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(
                            name, type, closingValue, dueValue, isDefault,
                            cardIdentifiers.ifBlank { null },
                            openingBalanceValue ?: java.math.BigDecimal.ZERO,
                            openingBalanceDateValue.takeIf {
                                type == FinancialAccountType.BANK_ACCOUNT
                            },
                        )
                    },
                    enabled = name.isNotBlank() && daysValid &&
                        (type == FinancialAccountType.CREDIT_CARD ||
                            (openingBalanceValue != null && openingBalanceDateValue != null)),
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CardInvoicesScreen(
        store: DiagnosticStore,
        account: FinancialAccountRecord,
        onBack: () -> Unit,
    ) {
        var refresh by remember(account.id) { mutableIntStateOf(0) }
        var selectedMonth by remember(account.id) { mutableStateOf(YearMonth.now()) }
        val invoices = remember(account.id, refresh) { store.creditCardInvoices(account.id) }
        val visibleInvoice = remember(invoices, selectedMonth) {
            invoices.firstOrNull { invoice ->
                (invoice.dueDate?.let(YearMonth::from) ?: invoice.closingPeriod) == selectedMonth
            }
        }
        var selectedInvoice by remember(account.id) {
            mutableStateOf<CreditCardInvoiceRecord?>(null)
        }
        selectedInvoice?.let { invoice ->
            InvoiceDetailScreen(
                store = store,
                account = account,
                invoice = invoice,
                onBack = { selectedInvoice = null },
                onPayment = { amount, paidAt, sourceAccountId ->
                    if (store.recordInvoicePayment(invoice, amount, paidAt, sourceAccountId)) {
                        refresh++
                        selectedInvoice = store.creditCardInvoices(account.id)
                            .firstOrNull { it.id == invoice.id }
                        true
                    } else false
                },
                onDeletePayment = { paymentId ->
                    if (store.deleteInvoicePayment(invoice, paymentId)) {
                        refresh++
                        selectedInvoice = store.creditCardInvoices(account.id)
                            .firstOrNull { it.id == invoice.id }
                        true
                    } else false
                },
                onInvoiceAdjustment = { officialTotal ->
                    if (store.adjustInvoiceTotal(invoice, officialTotal)) {
                        refresh++
                        selectedInvoice = null
                        true
                    } else false
                },
                onTransactionChanged = {
                    refresh++
                    selectedInvoice = null
                },
            )
            return
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(account.name) },
                    actions = { TextButton(onClick = onBack) { Text("Contas") } },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Faturas", style = MaterialTheme.typography.headlineSmall)
                }
                item {
                    MonthSelector(
                        selectedMonth = selectedMonth,
                        onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                        onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                    )
                }
                if (visibleInvoice == null) {
                    item {
                        Card {
                            Text(
                                "Nenhuma fatura em ${formatMonth(selectedMonth)}.",
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                            )
                        }
                    }
                }
                visibleInvoice?.let { invoice ->
                item(key = "invoice-${invoice.id}") {
                    Card(onClick = { selectedInvoice = invoice }) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val referenceMonth = invoice.dueDate?.let(YearMonth::from)
                                    ?: invoice.closingPeriod
                                Text(
                                    formatMonth(referenceMonth),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    formatCurrency(invoice.total.toPlainString()),
                                    color = if (invoice.total.signum() < 0) {
                                        Color(0xFF0A7D65)
                                    } else {
                                        Color(0xFFBA3B46)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                invoice.status.displayName + " · " +
                                    "${invoice.transactionCount} movimentações",
                            )
                            Text(
                                "Fecha em ${invoice.closingDate.format(SHORT_DATE_FORMATTER)}" +
                                    (invoice.dueDate?.let {
                                        " · vence em ${it.format(SHORT_DATE_FORMATTER)}"
                                    } ?: " · vencimento não informado"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InvoiceDetailScreen(
        store: DiagnosticStore,
        account: FinancialAccountRecord,
        invoice: CreditCardInvoiceRecord,
        onBack: () -> Unit,
        onPayment: (java.math.BigDecimal, LocalDate, Long?) -> Boolean,
        onDeletePayment: (Long) -> Boolean,
        onInvoiceAdjustment: (java.math.BigDecimal) -> Boolean,
        onTransactionChanged: () -> Unit,
    ) {
        val transactions = remember(invoice.id) { store.invoiceTransactions(invoice.id) }
        val payments = remember(invoice.id, invoice.paidAmount) { store.invoicePayments(invoice) }
        val bankAccounts = remember { store.financialAccounts().filter {
            it.type == FinancialAccountType.BANK_ACCOUNT
        } }
        val referenceMonth = invoice.dueDate?.let(YearMonth::from) ?: invoice.closingPeriod
        var addingPayment by remember(invoice.id) { mutableStateOf(false) }
        var paymentAmount by remember(invoice.id, invoice.outstandingAmount) {
            mutableStateOf(invoice.outstandingAmount.toPlainString().replace('.', ','))
        }
        var paymentDate by remember(invoice.id) { mutableStateOf(LocalDate.now().toString()) }
        var sourceAccountId by remember(invoice.id) {
            mutableStateOf(bankAccounts.singleOrNull()?.id)
        }
        var sourceAccountMenuExpanded by remember { mutableStateOf(false) }
        var deletingPayment by remember(invoice.id) {
            mutableStateOf<InvoicePaymentRecord?>(null)
        }
        var adjustingInvoice by remember(invoice.id) { mutableStateOf(false) }
        var editingInvoiceTransaction by remember(invoice.id) {
            mutableStateOf<FinancialTransactionRecord?>(null)
        }
        var officialTotal by remember(invoice.id, invoice.total) {
            mutableStateOf(invoice.total.toPlainString().replace('.', ','))
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(formatMonth(referenceMonth)) },
                    actions = { TextButton(onClick = onBack) { Text("Faturas") } },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                formatCurrency(invoice.total.toPlainString()),
                                color = if (invoice.total.signum() < 0) {
                                    Color(0xFF0A7D65)
                                } else {
                                    Color(0xFFBA3B46)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text("${invoice.status.displayName} · ${transactions.size} movimentações")
                            if (invoice.adjustmentAmount.signum() != 0) {
                                Text(
                                    (if (invoice.adjustmentAmount.signum() > 0) "Débito" else "Crédito") +
                                        " de ajuste: " +
                                        formatCurrency(invoice.adjustmentAmount.abs().toPlainString()),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (invoice.paidAmount.signum() > 0) {
                                Text("Pago: ${formatCurrency(invoice.paidAmount.toPlainString())}")
                            }
                            if (invoice.outstandingAmount.signum() > 0) {
                                Text(
                                    "Saldo: ${formatCurrency(invoice.outstandingAmount.toPlainString())}",
                                    color = Color(0xFFBA3B46),
                                )
                            }
                            OutlinedButton(
                                onClick = { adjustingInvoice = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Ajustar valor da fatura") }
                            Text(
                                "Fecha em ${invoice.closingDate.format(SHORT_DATE_FORMATTER)}" +
                                    (invoice.dueDate?.let {
                                        " · vence em ${it.format(SHORT_DATE_FORMATTER)}"
                                    } ?: " · vencimento não informado"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (invoice.outstandingAmount.signum() > 0) {
                                Button(
                                    onClick = { addingPayment = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Registrar pagamento") }
                            }
                        }
                    }
                }
                if (payments.isNotEmpty()) {
                    item { Text("Pagamentos", style = MaterialTheme.typography.titleLarge) }
                    items(payments, key = { "invoice-payment-${it.id}" }) { payment ->
                        Card {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        formatCurrency(payment.amount.toPlainString()),
                                        color = Color(0xFF0A7D65),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        payment.paidAt.format(SHORT_DATE_FORMATTER),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    payment.sourceAccountName?.let { name ->
                                        Text(
                                            "Pago com $name",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                TextButton(onClick = { deletingPayment = payment }) {
                                    Text("Excluir", color = Color(0xFFBA3B46))
                                }
                            }
                        }
                    }
                }
                item { Text("Lançamentos", style = MaterialTheme.typography.titleLarge) }
                items(transactions, key = { "invoice-transaction-${it.id}" }) { transaction ->
                    Card(onClick = { editingInvoiceTransaction = transaction }) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    transaction.description,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    (if (transaction.direction == FinancialTransactionDirection.EXPENSE) {
                                        "- "
                                    } else {
                                        "+ "
                                    }) + formatCurrency(transaction.amount),
                                    color = if (
                                        transaction.direction == FinancialTransactionDirection.EXPENSE
                                    ) Color(0xFFBA3B46) else Color(0xFF0A7D65),
                                )
                            }
                            val occurredAt = runCatching {
                                LocalDateTime.parse(transaction.occurredAt)
                            }.getOrNull()
                            val dateLabel = occurredAt?.toLocalDate()?.format(SHORT_DATE_FORMATTER)
                                ?.let { date ->
                                    if (transaction.origin == TransactionOrigin.MOBILLS) {
                                        "Referência $date"
                                    } else {
                                        date
                                    }
                                }
                            Text(
                                listOfNotNull(
                                    transaction.category.displayName,
                                    dateLabel,
                                    if (transaction.origin == TransactionOrigin.MOBILLS) {
                                        "Mobills"
                                    } else {
                                        "Notificação"
                                    },
                                    if (transaction.status == TransactionStatus.PENDING) {
                                        "Pendente"
                                    } else {
                                        "Realizada"
                                    },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (adjustingInvoice) {
            val officialValue = if (',' in officialTotal) {
                officialTotal.replace(".", "").replace(',', '.').toBigDecimalOrNull()
            } else officialTotal.toBigDecimalOrNull()
            AlertDialog(
                onDismissRequest = { adjustingInvoice = false },
                title = { Text("Ajustar valor da fatura") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Compras: ${formatCurrency(invoice.baseTotal.toPlainString())}")
                        OutlinedTextField(
                            value = officialTotal,
                            onValueChange = { value ->
                                officialTotal = value.filter {
                                    it.isDigit() || it == ',' || it == '.'
                                }
                            },
                            label = { Text("Total oficial da fatura") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        officialValue?.let { value ->
                            val difference = value - invoice.baseTotal
                            Text(
                                when {
                                    difference.signum() > 0 -> "Será lançado débito de " +
                                        formatCurrency(difference.toPlainString())
                                    difference.signum() < 0 -> "Será lançado crédito de " +
                                        formatCurrency(difference.abs().toPlainString())
                                    else -> "O ajuste existente será removido."
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (onInvoiceAdjustment(checkNotNull(officialValue))) {
                                adjustingInvoice = false
                            }
                        },
                        enabled = officialValue?.signum()?.let { it >= 0 } == true,
                    ) { Text("Salvar ajuste") }
                },
                dismissButton = {
                    TextButton(onClick = { adjustingInvoice = false }) { Text("Cancelar") }
                },
            )
        }
        editingInvoiceTransaction?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                onDismiss = { editingInvoiceTransaction = null },
                onSave = { description, category, status, amount, dueDate, plannedDate, paidAt, applyToFuture, scope ->
                    if (store.updateTransactionDetails(
                            transaction.id, description, category, status,
                            amount, dueDate, plannedDate, paidAt, applyToFuture, scope,
                        )) {
                        editingInvoiceTransaction = null
                        onTransactionChanged()
                    }
                },
            )
        }
        if (addingPayment) {
            val normalizedPaymentAmount = if (',' in paymentAmount) {
                paymentAmount.replace(".", "").replace(',', '.')
            } else {
                paymentAmount
            }
            val parsedAmount = normalizedPaymentAmount.toBigDecimalOrNull()
            val parsedDate = runCatching { LocalDate.parse(paymentDate) }.getOrNull()
            AlertDialog(
                onDismissRequest = { addingPayment = false },
                title = { Text("Registrar pagamento") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Saldo da fatura: " +
                                formatCurrency(invoice.outstandingAmount.toPlainString())
                        )
                        OutlinedTextField(
                            value = paymentAmount,
                            onValueChange = { value ->
                                paymentAmount = value.filter { it.isDigit() || it in ",." }.take(16)
                            },
                            label = { Text("Valor pago") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        if (bankAccounts.isNotEmpty()) {
                            Box {
                                OutlinedButton(
                                    onClick = { sourceAccountMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        bankAccounts.firstOrNull { it.id == sourceAccountId }?.name
                                            ?: "Selecionar conta de pagamento"
                                    )
                                }
                                DropdownMenu(
                                    expanded = sourceAccountMenuExpanded,
                                    onDismissRequest = { sourceAccountMenuExpanded = false },
                                ) {
                                    bankAccounts.forEach { bankAccount ->
                                        DropdownMenuItem(
                                            text = { Text(bankAccount.name) },
                                            onClick = {
                                                sourceAccountId = bankAccount.id
                                                sourceAccountMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        DatePickerField(
                            paymentDate,
                            "Data do pagamento",
                            onValueChange = { paymentDate = it },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (
                                onPayment(
                                    checkNotNull(parsedAmount),
                                    checkNotNull(parsedDate),
                                    sourceAccountId,
                                )
                            ) {
                                addingPayment = false
                            }
                        },
                        enabled = parsedAmount != null && parsedAmount.signum() > 0 &&
                            parsedAmount <= invoice.outstandingAmount && parsedDate != null &&
                            (bankAccounts.isEmpty() || sourceAccountId != null),
                    ) { Text("Confirmar") }
                },
                dismissButton = {
                    TextButton(onClick = { addingPayment = false }) { Text("Cancelar") }
                },
            )
        }
        deletingPayment?.let { payment ->
            AlertDialog(
                onDismissRequest = { deletingPayment = null },
                title = { Text("Excluir pagamento?") },
                text = {
                    Text(
                        formatCurrency(payment.amount.toPlainString()) + " em " +
                            payment.paidAt.format(SHORT_DATE_FORMATTER)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (onDeletePayment(payment.id)) deletingPayment = null
                    }) { Text("Excluir", color = Color(0xFFBA3B46)) }
                },
                dismissButton = {
                    TextButton(onClick = { deletingPayment = null }) { Text("Cancelar") }
                },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DatePickerField(
        value: String,
        label: String,
        allowClear: Boolean = false,
        onValueChange: (String) -> Unit,
    ) {
        var showingPicker by remember { mutableStateOf(false) }
        val selectedDate = value.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { showingPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "$label: " + (selectedDate?.format(SHORT_DATE_FORMATTER)
                        ?: "Selecionar data")
                )
            }
            if (allowClear && value.isNotBlank()) {
                TextButton(onClick = { onValueChange("") }) { Text("Limpar $label") }
            }
        }
        if (showingPicker) {
            val initialMillis = selectedDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = initialMillis,
            )
            DatePickerDialog(
                onDismissRequest = { showingPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onValueChange(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                            )
                        }
                        showingPicker = false
                    }) { Text("Confirmar") }
                },
                dismissButton = {
                    TextButton(onClick = { showingPicker = false }) { Text("Cancelar") }
                },
            ) {
                DatePicker(state = pickerState)
            }
        }
    }

    @Composable
    private fun MonthSelector(
        selectedMonth: YearMonth,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
    ) {
        val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(selectedMonth, swipeThreshold) {
                        var horizontalDistance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDistance = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                horizontalDistance += dragAmount
                            },
                            onDragEnd = {
                                when {
                                    horizontalDistance > swipeThreshold -> onPrevious()
                                    horizontalDistance < -swipeThreshold -> onNext()
                                }
                            },
                        )
                    }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrevious) { Text("‹") }
                Text(
                    text = formatMonth(selectedMonth),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onNext) { Text("›") }
            }
        }
    }

    @Composable
    private fun StatementSummary(
        statement: MonthlyStatement,
        generalProjectedBalance: java.math.BigDecimal,
    ) {
        val balanceColor = if (statement.balance.signum() < 0) {
            Color(0xFFBA3B46)
        } else {
            Color(0xFF0A7D65)
        }

        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Resultado realizado", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = formatCurrency(statement.balance.toPlainString()),
                    color = balanceColor,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Somente valores pagos ou recebidos",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    SummaryValue(
                        label = "Entradas",
                        amount = statement.totalIncome.toPlainString(),
                        color = Color(0xFF0A7D65),
                        modifier = Modifier.weight(1f),
                    )
                    SummaryValue(
                        label = "Despesas",
                        amount = statement.totalExpense.toPlainString(),
                        color = Color(0xFFBA3B46),
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
                Text("Resultado previsto", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = formatCurrency(statement.projectedBalance.toPlainString()),
                    color = if (statement.projectedBalance.signum() < 0) {
                        Color(0xFFBA3B46)
                    } else {
                        Color(0xFF0A7D65)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Inclui pendências: + " +
                        formatCurrency(statement.pendingIncome.toPlainString()) +
                        " / - " + formatCurrency(statement.pendingExpense.toPlainString()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("Saldo geral projetado", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = formatCurrency(generalProjectedBalance.toPlainString()),
                    color = if (generalProjectedBalance.signum() < 0) {
                        Color(0xFFBA3B46)
                    } else Color(0xFF0A7D65),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Saldo acumulado das contas bancárias até o fim de " +
                        formatMonth(statement.period),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun ExpenseByCategoryCard(
        summaries: List<CategoryExpenseSummary>,
    ) {
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Despesas por categoria",
                    style = MaterialTheme.typography.titleMedium,
                )
                summaries.forEachIndexed { index, summary ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = summary.category.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = if (summary.transactionCount == 1) {
                                        "1 movimentação"
                                    } else {
                                        "${summary.transactionCount} movimentações"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatCurrency(summary.total.toPlainString()),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "${summary.sharePercent}% das despesas",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { summary.sharePercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SummaryValue(
        label: String,
        amount: String,
        color: Color,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = formatCurrency(amount),
                color = color,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }

    @Composable
    private fun StatementInvoiceCard(
        item: StatementInvoiceItem,
        onClick: () -> Unit,
    ) {
        val invoice = item.invoice
        val transaction = item.transaction
        val isCredit = transaction.direction == FinancialTransactionDirection.INCOME
        Card(onClick = onClick) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        transaction.description,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        (if (isCredit) "+ " else "- ") +
                            formatCurrency(transaction.amount),
                        color = if (isCredit) Color(0xFF0A7D65) else Color(0xFFBA3B46),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    "${invoice.status.displayName} · ${invoice.transactionCount} compras" +
                        if (invoice.paidAmount.signum() > 0) {
                            " · pago ${formatCurrency(invoice.paidAmount.toPlainString())}"
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Vencimento ${invoice.dueDate?.format(SHORT_DATE_FORMATTER)} · toque para detalhes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun TransactionCard(
        transaction: FinancialTransactionRecord,
        onClick: () -> Unit,
    ) {
        val isIncome = transaction.direction == FinancialTransactionDirection.INCOME
        val amountPrefix = if (isIncome) "+ " else "- "
        val amountColor = if (isIncome) Color(0xFF0A7D65) else Color(0xFFBA3B46)

        Card(onClick = onClick) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = transaction.description,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = amountPrefix + formatCurrency(transaction.amount),
                        color = amountColor,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = transactionTypeLabel(transaction.type) +
                            " · " + transaction.category.displayName +
                            if (transaction.categorySource == TransactionCategorySource.RULE) {
                                " · automática"
                            } else {
                                ""
                            } + if (transaction.status == TransactionStatus.PENDING) {
                                " · pendente"
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatTime(transaction.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (
                    transaction.dueDate != null || transaction.plannedPaymentDate != null ||
                    transaction.paidAt != null
                ) {
                    Text(
                        listOfNotNull(
                            transaction.dueDate?.let { "Vence $it" },
                            transaction.plannedPaymentDate?.let { "Previsto $it" },
                            transaction.paidAt?.let { "Pago $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun EditTransactionDialog(
        transaction: FinancialTransactionRecord,
        onDismiss: () -> Unit,
        onSave: (
            String, TransactionCategory, TransactionStatus, java.math.BigDecimal,
            LocalDate?, LocalDate?, LocalDate?, Boolean, TransactionSeriesScope,
        ) -> Unit,
        onDelete: ((TransactionSeriesScope) -> Unit)? = null,
    ) {
        var description by remember(transaction.id) {
            mutableStateOf(transaction.description)
        }
        var selectedCategory by remember(transaction.id) {
            mutableStateOf(transaction.category)
        }
        var categoryMenuExpanded by remember(transaction.id) {
            mutableStateOf(false)
        }
        var applyToFuture by remember(transaction.id) {
            mutableStateOf(false)
        }
        var selectedStatus by remember(transaction.id) {
            mutableStateOf(transaction.status)
        }
        var amount by remember(transaction.id) {
            mutableStateOf(transaction.amount.replace('.', ','))
        }
        var dueDate by remember(transaction.id) { mutableStateOf(transaction.dueDate.orEmpty()) }
        var plannedPaymentDate by remember(transaction.id) {
            mutableStateOf(transaction.plannedPaymentDate.orEmpty())
        }
        var paidAt by remember(transaction.id) { mutableStateOf(transaction.paidAt.orEmpty()) }
        var seriesScope by remember(transaction.id) {
            mutableStateOf(TransactionSeriesScope.ONLY_THIS)
        }
        val amountValue = if (',' in amount) {
            amount.replace(".", "").replace(',', '.').toBigDecimalOrNull()
        } else amount.toBigDecimalOrNull()
        val dueDateValue = dueDate.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val paidAtValue = paidAt.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val plannedPaymentDateValue = plannedPaymentDate.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val datesValid = (dueDate.isBlank() || dueDateValue != null) &&
            (plannedPaymentDate.isBlank() || plannedPaymentDateValue != null) &&
            (paidAt.isBlank() || paidAtValue != null)
        val availableCategories = remember(transaction.direction) {
            TransactionCategory.availableFor(transaction.direction)
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Editar movimentação") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { newValue ->
                            if (newValue.length <= DESCRIPTION_MAX_LENGTH) {
                                description = newValue
                            }
                        },
                        label = { Text("Descrição") },
                        supportingText = {
                            Text("${description.length}/$DESCRIPTION_MAX_LENGTH")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { value ->
                            amount = value.filter { it.isDigit() || it == ',' || it == '.' }
                        },
                        label = { Text("Valor") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DatePickerField(
                        dueDate, "Vencimento", allowClear = true,
                        onValueChange = { dueDate = it },
                    )
                    DatePickerField(
                        plannedPaymentDate, "Pagamento previsto", allowClear = true,
                        onValueChange = { plannedPaymentDate = it },
                    )
                    DatePickerField(
                        paidAt, "Pagamento", allowClear = true,
                        onValueChange = {
                            paidAt = it
                            selectedStatus = if (it.isNotBlank()) {
                                TransactionStatus.REALIZED
                            } else TransactionStatus.PENDING
                        },
                    )
                    transaction.originalAmount?.let {
                        Text(
                            "Valor original: ${formatCurrency(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (transaction.seriesId != null) {
                        Text(
                            "Série ${transaction.seriesIndex}/${transaction.seriesTotal}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Column {
                            listOf(
                                TransactionSeriesScope.ONLY_THIS to "Somente esta",
                                TransactionSeriesScope.THIS_AND_FUTURE to "Esta e as próximas",
                                TransactionSeriesScope.ALL to "Todas da série",
                            ).forEach { (scope, label) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = seriesScope == scope,
                                        onClick = { seriesScope = scope },
                                    )
                                    Text(label)
                                }
                            }
                        }
                        Text(
                            "O alcance altera descrição, categoria e valor. Datas e situação " +
                                "continuam individuais.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Categoria",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { categoryMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedCategory.displayName)
                        }
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false },
                        ) {
                            availableCategories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.displayName) },
                                    onClick = {
                                        selectedCategory = category
                                        categoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = selectedStatus == TransactionStatus.REALIZED,
                            onCheckedChange = { checked ->
                                selectedStatus = if (checked) {
                                    TransactionStatus.REALIZED
                                } else {
                                    paidAt = ""
                                    TransactionStatus.PENDING
                                }
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (transaction.direction == FinancialTransactionDirection.INCOME) {
                                if (selectedStatus == TransactionStatus.REALIZED) {
                                    "Recebido"
                                } else {
                                    "Não recebido"
                                }
                            } else {
                                if (selectedStatus == TransactionStatus.REALIZED) {
                                    "Pago"
                                } else {
                                    "Não pago"
                                }
                            }
                        )
                    }
                    if (
                        TransactionCategoryRule.canApplyToFuture(
                            type = transaction.type,
                            category = selectedCategory,
                            ruleKey = transaction.ruleKey,
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = applyToFuture,
                                onCheckedChange = { applyToFuture = it },
                            )
                            Text("Aplicar a compras futuras deste estabelecimento")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(
                            description, selectedCategory, selectedStatus,
                            checkNotNull(amountValue), dueDateValue, plannedPaymentDateValue,
                            paidAtValue, applyToFuture,
                            seriesScope,
                        )
                    },
                    enabled = description.isNotBlank() && amountValue?.signum() == 1 && datesValid,
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                Row {
                    onDelete?.let {
                        TextButton(onClick = { it(seriesScope) }) {
                            Text("Excluir", color = Color(0xFFBA3B46))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                }
            },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TransactionSearchScreen(
        store: DiagnosticStore,
        onBack: () -> Unit,
    ) {
        var refresh by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        var fromDate by remember { mutableStateOf("") }
        var toDate by remember { mutableStateOf("") }
        var statusFilter by remember { mutableStateOf<TransactionStatus?>(null) }
        var editing by remember { mutableStateOf<FinancialTransactionRecord?>(null) }
        val results = remember(refresh, query, fromDate, toDate, statusFilter) {
            val from = fromDate.takeIf(String::isNotBlank)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }
            val to = toDate.takeIf(String::isNotBlank)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }
            store.recentTransactions(10_000).filter { transaction ->
                val date = transactionEffectiveDate(transaction)
                transaction.description.contains(query.trim(), ignoreCase = true) &&
                    (statusFilter == null || transaction.status == statusFilter) &&
                    (from == null || (date != null && !date.isBefore(from))) &&
                    (to == null || (date != null && !date.isAfter(to)))
            }.sortedByDescending { transactionEffectiveDate(it) }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pesquisar movimentações") },
                    actions = { TextButton(onClick = onBack) { Text("Extrato") } },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Nome ou descrição") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            DatePickerField(
                                fromDate, "De", allowClear = true,
                                onValueChange = { fromDate = it },
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            DatePickerField(
                                toDate, "Até", allowClear = true,
                                onValueChange = { toDate = it },
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = statusFilter == null,
                            onClick = { statusFilter = null },
                            label = { Text("Todas") },
                        )
                        FilterChip(
                            selected = statusFilter == TransactionStatus.REALIZED,
                            onClick = { statusFilter = TransactionStatus.REALIZED },
                            label = { Text("Pagas") },
                        )
                        FilterChip(
                            selected = statusFilter == TransactionStatus.PENDING,
                            onClick = { statusFilter = TransactionStatus.PENDING },
                            label = { Text("Não pagas") },
                        )
                    }
                }
                item { Text("${results.size} resultados", style = MaterialTheme.typography.labelLarge) }
                items(results, key = { "search-${it.id}" }) { transaction ->
                    TransactionCard(transaction) { editing = transaction }
                }
            }
        }
        editing?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                onDismiss = { editing = null },
                onSave = { description, category, status, amount, dueDate, plannedDate, paidAt, apply, scope ->
                    if (store.updateTransactionDetails(
                            transaction.id, description, category, status,
                            amount, dueDate, plannedDate, paidAt, apply, scope,
                        )) {
                        editing = null
                        refresh++
                    }
                },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PlanningScreen(
        store: DiagnosticStore,
        onBack: () -> Unit,
    ) {
        var horizonDays by remember { mutableIntStateOf(30) }
        val today = remember { LocalDate.now() }
        val allPending = remember {
            consolidatedTransactions(store)
                .asSequence()
                .filter { it.status == TransactionStatus.PENDING }
                .mapNotNull { transaction ->
                    transactionEffectiveDate(transaction)?.let { PlanningItem(transaction, it) }
                }
                .filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(90)) }
                .sortedBy { it.date }
                .toList()
        }
        val visible = remember(allPending, horizonDays) {
            allPending.filter { !it.date.isAfter(today.plusDays(horizonDays.toLong())) }
        }
        val income = visible.filter {
            it.transaction.direction == FinancialTransactionDirection.INCOME
        }.sumOf { it.transaction.amount.toBigDecimal() }
        val expense = visible.filter {
            it.transaction.direction == FinancialTransactionDirection.EXPENSE
        }.sumOf { it.transaction.amount.toBigDecimal() }
        val net = income - expense
        val projectedBalance = remember(horizonDays) {
            store.generalProjectedBalance(today.plusDays(horizonDays.toLong()))
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Planejamento") },
                    actions = { TextButton(onClick = onBack) { Text("Extrato") } },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(30, 60, 90).forEach { days ->
                            FilterChip(
                                selected = horizonDays == days,
                                onClick = { horizonDays = days },
                                label = { Text("$days dias") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    Card {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Pendências do período", style = MaterialTheme.typography.titleMedium)
                            Text("Entradas: ${formatCurrency(income.toPlainString())}", color = Color(0xFF087F67))
                            Text("Saídas: - ${formatCurrency(expense.toPlainString())}", color = MaterialTheme.colorScheme.error)
                            HorizontalDivider()
                            Text(
                                "Resultado: ${formatCurrency(net.toPlainString())}",
                                color = financialValueColor(net.signum()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Saldo geral projetado: ${formatCurrency(projectedBalance.toPlainString())}",
                                color = financialValueColor(projectedBalance.signum()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        Card {
                            Text(
                                "Nenhuma pendência nos próximos $horizonDays dias.",
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                visible.groupBy { YearMonth.from(it.date) }.forEach { (month, monthItems) ->
                    item(key = "planning-month-$month") {
                        Text(formatMonth(month), style = MaterialTheme.typography.titleLarge)
                    }
                    items(monthItems, key = { "planning-${it.transaction.id}" }) { item ->
                        Card {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.transaction.description, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        item.date.format(SHORT_DATE_FORMATTER) +
                                            (item.transaction.account?.let { " · $it" } ?: "") +
                                            (item.transaction.seriesIndex?.let { index ->
                                                " · $index/${item.transaction.seriesTotal}"
                                            } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                val expenseItem = item.transaction.direction ==
                                    FinancialTransactionDirection.EXPENSE
                                Text(
                                    (if (expenseItem) "- " else "+ ") +
                                        formatCurrency(item.transaction.amount),
                                    color = if (expenseItem) MaterialTheme.colorScheme.error
                                    else Color(0xFF087F67),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AnnualSummaryScreen(
        store: DiagnosticStore,
        onBack: () -> Unit,
    ) {
        var selectedYear by remember { mutableIntStateOf(LocalDate.now().year) }
        val transactions = remember(selectedYear) { consolidatedTransactions(store) }
        val rows = remember(selectedYear, transactions) {
            (1..12).map { month ->
                val period = YearMonth.of(selectedYear, month)
                Triple(
                    period,
                    MonthlyStatementCalculator.calculate(period, transactions),
                    store.generalProjectedBalance(period.atEndOfMonth()),
                )
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Resumo anual") },
                    actions = { TextButton(onClick = onBack) { Text("Extrato") } },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { selectedYear-- }) { Text("‹") }
                            Text(
                                selectedYear.toString(), Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            TextButton(onClick = { selectedYear++ }) { Text("›") }
                        }
                    }
                }
                item {
                    Card {
                        Column(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            Row {
                                AnnualSummaryCell("Mês", header = true, firstColumn = true)
                                AnnualSummaryCell("Entradas", header = true)
                                AnnualSummaryCell("Saídas", header = true)
                                AnnualSummaryCell("Resultado", header = true)
                                AnnualSummaryCell("Saldo projetado", header = true)
                            }
                            HorizontalDivider()
                            rows.forEachIndexed { index, (period, statement, projectedBalance) ->
                                val income = statement.projectedIncome
                                val expense = statement.projectedExpense
                                val result = statement.projectedBalance
                                Row {
                                    AnnualSummaryCell(
                                        formatMonth(period).substringBefore(" de "),
                                        firstColumn = true,
                                    )
                                    AnnualSummaryCell(
                                        formatCurrency(income.toPlainString()),
                                        valueColor = financialValueColor(income.signum()),
                                    )
                                    AnnualSummaryCell(
                                        if (expense.signum() > 0) {
                                            "- ${formatCurrency(expense.toPlainString())}"
                                        } else formatCurrency(expense.toPlainString()),
                                        valueColor = financialValueColor(-expense.signum()),
                                    )
                                    AnnualSummaryCell(
                                        formatCurrency(result.toPlainString()),
                                        valueColor = financialValueColor(result.signum()),
                                    )
                                    AnnualSummaryCell(
                                        formatCurrency(projectedBalance.toPlainString()),
                                        valueColor = financialValueColor(projectedBalance.signum()),
                                    )
                                }
                                if (index < rows.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AnnualSummaryCell(
        text: String,
        header: Boolean = false,
        firstColumn: Boolean = false,
        valueColor: Color = Color.Unspecified,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .width(if (firstColumn) 130.dp else 112.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            style = if (header) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.bodySmall,
            textAlign = if (firstColumn) TextAlign.Start else TextAlign.End,
            color = valueColor,
        )
    }

    @Composable
    private fun financialValueColor(sign: Int): Color = when {
        sign > 0 -> Color(0xFF087F67)
        sign < 0 -> MaterialTheme.colorScheme.error
        else -> Color.Unspecified
    }

    private fun transactionEffectiveDate(transaction: FinancialTransactionRecord): LocalDate? {
        val stored = if (transaction.status == TransactionStatus.REALIZED) {
            transaction.paidAt
        } else transaction.plannedPaymentDate ?: transaction.dueDate
        return stored?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: runCatching { LocalDateTime.parse(transaction.occurredAt).toLocalDate() }.getOrNull()
    }

    private fun consolidatedTransactions(store: DiagnosticStore): List<FinancialTransactionRecord> {
        val transactions = store.recentTransactions(10_000)
        val cards = store.financialAccounts().filter {
            it.type == FinancialAccountType.CREDIT_CARD
        }
        val cardIds = cards.map { it.id }.toSet()
        val invoices = cards.flatMap { account ->
            store.creditCardInvoices(account.id).mapNotNull { invoice ->
                val due = invoice.dueDate ?: return@mapNotNull null
                if (invoice.total.signum() == 0) return@mapNotNull null
                val paidAt = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                    store.invoicePayments(invoice).maxOfOrNull { it.paidAt }
                } else null
                FinancialTransactionRecord(
                    id = -invoice.id,
                    sourceEventId = null,
                    direction = if (invoice.total.signum() < 0) {
                        FinancialTransactionDirection.INCOME
                    } else FinancialTransactionDirection.EXPENSE,
                    type = if (invoice.total.signum() < 0) {
                        FinancialTransactionType.IMPORTED_INCOME
                    } else FinancialTransactionType.IMPORTED_EXPENSE,
                    amount = invoice.total.abs().toPlainString(),
                    occurredAt = due.atStartOfDay().toString(),
                    description = "Fatura ${account.name}",
                    sourcePackage = "credit-card-invoice",
                    status = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                        TransactionStatus.REALIZED
                    } else TransactionStatus.PENDING,
                    dueDate = due.toString(),
                    paidAt = paidAt?.toString(),
                )
            }
        }
        return transactions.filterNot { transaction ->
            transaction.type == FinancialTransactionType.CARD_PURCHASE ||
                (transaction.accountId != null && transaction.accountId in cardIds)
        } + invoices
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DiagnosticScreen(
        store: DiagnosticStore,
        preferences: BankPackagePreferences,
        onOpenStatement: () -> Unit,
    ) {
        var refresh by remember { mutableIntStateOf(0) }
        val allowed = remember(refresh) { preferences.allowedPackages() }
        val candidates = remember(refresh) { store.candidates() }
        val events = remember(refresh) { store.recentEvents() }
        val enabled = remember(refresh) { notificationAccessEnabled() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Diagnóstico financeiro") },
                    actions = {
                        TextButton(onClick = onOpenStatement) {
                            Text("Extrato")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (enabled) {
                                    "Acesso às notificações ativo"
                                } else {
                                    "Acesso às notificações desativado"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("O conteúdo só é armazenado depois que você autoriza um aplicativo específico.")
                            Button(
                                onClick = {
                                    startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    )
                                },
                            ) {
                                Text("Configurar acesso")
                            }
                            OutlinedButton(onClick = { refresh++ }) {
                                Text("Atualizar")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Aplicativos detectados",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (candidates.isEmpty()) {
                    item {
                        Text("Após ativar o acesso, aguarde uma notificação do banco e toque em Atualizar.")
                    }
                }
                items(candidates) { (packageName, label) ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                Text(packageName, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = packageName in allowed,
                                onCheckedChange = {
                                    if (it) {
                                        preferences.allow(packageName)
                                    } else {
                                        preferences.remove(packageName)
                                    }
                                    refresh++
                                },
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Eventos autorizados",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        TextButton(
                            onClick = {
                                store.clearEvents()
                                refresh++
                            },
                        ) {
                            Text("Limpar")
                        }
                    }
                }
                if (events.isEmpty()) {
                    item { Text("Nenhum evento armazenado.") }
                }
                items(events, key = { it.id }) { event ->
                    Card {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(event.title, style = MaterialTheme.typography.titleMedium)
                            Text(event.body)
                            val statusText = when (event.classification) {
                                NotificationClassification.TRANSACTION -> when (event.transactionType) {
                                    FinancialTransactionType.CARD_PURCHASE ->
                                        "Reconhecida: final ${event.cardLastFour} · ${formatCurrency(event.amount)} · ${event.merchant}"
                                    FinancialTransactionType.PIX_RECEIVED ->
                                        "Entrada reconhecida: PIX · ${formatCurrency(event.amount)}"
                                    FinancialTransactionType.IMPORTED_EXPENSE,
                                    FinancialTransactionType.IMPORTED_INCOME,
                                    FinancialTransactionType.MANUAL_EXPENSE,
                                    FinancialTransactionType.MANUAL_INCOME ->
                                        "Movimentação importada"
                                    null -> "Transação reconhecida"
                                }
                                NotificationClassification.IGNORED_PROMOTION ->
                                    "Ignorada: ${event.classificationReason ?: "promoção"}"
                                NotificationClassification.PENDING_RULE -> "Aguardando regra"
                            }
                            val statusColor = when (event.classification) {
                                NotificationClassification.TRANSACTION -> Color(0xFF0A7D65)
                                NotificationClassification.IGNORED_PROMOTION ->
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                NotificationClassification.PENDING_RULE -> Color(0xFFBA3B46)
                            }
                            Text(statusText, color = statusColor)
                        }
                    }
                }
            }
        }
    }

    private fun transactionTypeLabel(type: FinancialTransactionType): String =
        when (type) {
            FinancialTransactionType.CARD_PURCHASE -> "Compra no cartão"
            FinancialTransactionType.PIX_RECEIVED -> "PIX recebido"
            FinancialTransactionType.IMPORTED_EXPENSE -> "Despesa importada"
            FinancialTransactionType.IMPORTED_INCOME -> "Receita importada"
            FinancialTransactionType.MANUAL_EXPENSE -> "Despesa manual"
            FinancialTransactionType.MANUAL_INCOME -> "Receita manual"
        }

    private fun notificationAccessEnabled(): Boolean {
        val component = ComponentName(this, FinanceNotificationListener::class.java)
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(":")
            ?.any { ComponentName.unflattenFromString(it) == component } == true
    }

    private fun formatCurrency(amount: String?): String =
        amount?.toBigDecimalOrNull()?.let {
            NumberFormat.getCurrencyInstance(PT_BR).format(it)
        } ?: "valor indisponível"

    private fun formatMonth(period: YearMonth): String {
        val formatted = period.format(MONTH_FORMATTER)
        return formatted.take(1).uppercase(PT_BR) + formatted.drop(1)
    }

    private fun formatDate(date: LocalDate): String =
        date.format(DATE_FORMATTER)

    private fun formatTime(occurredAt: String): String =
        runCatching {
            LocalDateTime.parse(occurredAt).format(TIME_FORMATTER)
        }.getOrDefault("--:--")

    private companion object {
        val PT_BR: Locale = Locale("pt", "BR")
        val MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMMM 'de' yyyy", PT_BR)
        val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd 'de' MMMM", PT_BR)
        val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm", PT_BR)
        val SHORT_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR)
        const val DESCRIPTION_MAX_LENGTH = 80
    }
}
