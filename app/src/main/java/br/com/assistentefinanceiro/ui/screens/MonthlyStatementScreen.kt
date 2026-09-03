package br.com.assistentefinanceiro.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.com.assistentefinanceiro.notifications.*
import br.com.assistentefinanceiro.importing.MobillsImportAnalyzer
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.importing.SimpleXlsxReader
import br.com.assistentefinanceiro.ui.financeIcon
import br.com.assistentefinanceiro.ui.components.FinanceEmptyState
import br.com.assistentefinanceiro.ui.components.FinanceIconTile
import br.com.assistentefinanceiro.ui.components.FinanceNoticeCard
import br.com.assistentefinanceiro.ui.components.FinanceStatusPill
import br.com.assistentefinanceiro.ui.theme.AssistenteFinanceiroTheme
import br.com.assistentefinanceiro.ui.theme.FinanceSpacing
import br.com.assistentefinanceiro.ui.theme.FinanceTextStyles
import br.com.assistentefinanceiro.ui.theme.financeColors
import java.text.NumberFormat
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthlyStatementScreen(
    store: DiagnosticStore,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var pendingOnly by remember { mutableStateOf(false) }
    var withoutCategoryOnly by remember { mutableStateOf(false) }
    var withoutSubcategoryOnly by remember { mutableStateOf(false) }
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
    var generalProjectedBalance by remember(refresh, selectedMonth) {
        mutableStateOf<LoadState<java.math.BigDecimal>>(LoadState.Loading)
    }
    LaunchedEffect(refresh, selectedMonth) {
        generalProjectedBalance = try {
            LoadState.Ready(
                withContext(Dispatchers.IO) {
                    store.generalProjectedBalance(selectedMonth.atEndOfMonth())
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LoadState.Failed
        }
    }
    val visibleGroups = remember(
        statement.groups,
        pendingOnly,
        withoutCategoryOnly,
        withoutSubcategoryOnly,
    ) {
        statement.groups.mapNotNull { group ->
            group.copy(
                transactions = group.transactions.filter {
                    (!pendingOnly || it.status == TransactionStatus.PENDING) &&
                        (
                            (!withoutCategoryOnly && !withoutSubcategoryOnly) ||
                                (
                                    withoutCategoryOnly &&
                                        it.category == TransactionCategory.UNCATEGORIZED
                                    ) ||
                                (
                                    withoutSubcategoryOnly &&
                                        it.category != TransactionCategory.UNCATEGORIZED &&
                                        it.subcategory.isNullOrBlank()
                                    )
                            )
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Assistente Financeiro") },
                actions = {
                    IconButton(
                        onClick = { refresh++ },
                    ) {
                        Icon(
                            Icons.Rounded.Sync,
                            contentDescription = "Atualizar movimentações",
                        )
                    }
                    IconButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                )
                            )
                        },
                        enabled = !readingImport,
                    ) {
                        if (readingImport) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.FileUpload,
                                contentDescription = "Importar dados do Mobills",
                            )
                        }
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
                .padding(padding)
                .padding(horizontal = FinanceSpacing.md),
            contentPadding = PaddingValues(vertical = FinanceSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            if (readingImport) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }
            item {
                MonthSelector(
                    selectedMonth = selectedMonth,
                    onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                    onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                )
            }
            item {
                StatementSummary(
                    statement = statement,
                    generalProjectedBalance = generalProjectedBalance,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                    ) {
                        FilterChip(
                            selected = pendingOnly,
                            onClick = { pendingOnly = !pendingOnly },
                            label = { Text("Pendentes") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = withoutCategoryOnly,
                            onClick = { withoutCategoryOnly = !withoutCategoryOnly },
                            label = { Text("Sem categoria") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.HelpOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = withoutSubcategoryOnly,
                            onClick = { withoutSubcategoryOnly = !withoutSubcategoryOnly },
                            label = { Text("Sem subcategoria") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.AccountTree,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (pendingOnly || withoutCategoryOnly || withoutSubcategoryOnly) {
                            TextButton(
                                onClick = {
                                    pendingOnly = false
                                    withoutCategoryOnly = false
                                    withoutSubcategoryOnly = false
                                },
                            ) { Text("Limpar") }
                        }
                    }
                }
            }
            if (unconsolidatedCardTransactionCount > 0) {
                item {
                    FinanceNoticeCard(
                        icon = Icons.Rounded.CreditCardOff,
                        title = "Compras sem fatura",
                        description = "$unconsolidatedCardTransactionCount compras de cartão " +
                            "ainda não foram vinculadas a uma fatura com vencimento. " +
                            "Revise o cadastro do cartão em Contas.",
                        foreground = MaterialTheme.colorScheme.onErrorContainer,
                        background = MaterialTheme.colorScheme.errorContainer,
                    )
                }
            }
            if (visibleGroups.isEmpty()) {
                item {
                    val filtersActive = pendingOnly || withoutCategoryOnly ||
                        withoutSubcategoryOnly
                    FinanceEmptyState(
                        icon = if (filtersActive) {
                            Icons.Rounded.SearchOff
                        } else {
                            Icons.Rounded.ReceiptLong
                        },
                        title = if (filtersActive) {
                            "Nenhuma movimentação com estes filtros"
                        } else {
                            "Nenhuma movimentação em ${formatMonth(selectedMonth)}"
                        },
                        description = if (filtersActive) {
                            "Remova um ou mais filtros para ampliar os resultados."
                        } else {
                            "As movimentações reconhecidas ou importadas aparecerão aqui."
                        },
                        actionLabel = if (filtersActive) "Limpar filtros" else "Importar dados",
                        onAction = {
                            if (filtersActive) {
                                pendingOnly = false
                                withoutCategoryOnly = false
                                withoutSubcategoryOnly = false
                            } else {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument." +
                                            "spreadsheetml.sheet"
                                    )
                                )
                            }
                        },
                    )
                }
            }
            visibleGroups.forEach { group ->
                item(key = "date-${group.date}") {
                    Text(
                        text = formatDate(group.date),
                        modifier = Modifier.padding(
                            top = FinanceSpacing.sm,
                            start = FinanceSpacing.xxs,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            store = store,
            transaction = transaction,
            onDismiss = { editingTransaction = null },
            onSave = { description, category, customCategory, subcategory, status, amount, dueDate, plannedDate, paidAt, applyToFuture, scope ->
                if (
                    store.updateTransactionDetails(
                        transactionId = transaction.id,
                        description = description,
                        category = category,
                        customCategory = customCategory,
                        subcategory = subcategory,
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
                Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
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

@Composable
private fun StatementSummary(
    statement: MonthlyStatement,
    generalProjectedBalance: LoadState<java.math.BigDecimal>,
) {
    val semantic = MaterialTheme.financeColors

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FinanceIconTile(
                    icon = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                )
                Spacer(Modifier.width(FinanceSpacing.sm))
                Column {
                    Text(
                        "Saldo geral projetado",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Até o fim de ${formatMonth(statement.period)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (val balance = generalProjectedBalance) {
                LoadState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
                LoadState.Failed -> Text(
                    text = "Saldo indisponível",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is LoadState.Ready -> Text(
                    text = formatCurrency(balance.value.toPlainString()),
                    color = if (balance.value.signum() < 0) {
                        semantic.expense
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = FinanceTextStyles.moneyHero,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.md),
            ) {
                SummaryValue(
                    label = "Entradas",
                    amount = statement.totalIncome.toPlainString(),
                    prefix = "+ ",
                    color = semantic.income,
                    modifier = Modifier.weight(1f),
                )
                SummaryValue(
                    label = "Saídas",
                    amount = statement.totalExpense.toPlainString(),
                    prefix = "− ",
                    color = semantic.expense,
                    modifier = Modifier.weight(1f),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FinanceSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.md),
                ) {
                    SummaryValue(
                        label = "Resultado realizado",
                        amount = statement.balance.toPlainString(),
                        color = if (statement.balance.signum() < 0) {
                            semantic.expense
                        } else semantic.income,
                        modifier = Modifier.weight(1f),
                    )
                    SummaryValue(
                        label = "Resultado previsto",
                        amount = statement.projectedBalance.toPlainString(),
                        color = if (statement.projectedBalance.signum() < 0) {
                            semantic.expense
                        } else semantic.income,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (
                statement.pendingIncome.signum() != 0 ||
                statement.pendingExpense.signum() != 0
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = semantic.pending,
                    )
                    Text(
                        text = "Pendências: + " +
                            formatCurrency(statement.pendingIncome.toPlainString()) +
                            " / − " +
                            formatCurrency(statement.pendingExpense.toPlainString()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpenseByCategoryCard(
    summaries: List<CategoryExpenseSummary>,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            Text(
                text = "Despesas por categoria",
                style = MaterialTheme.typography.titleMedium,
            )
            summaries.forEachIndexed { index, summary ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
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
private fun StatementInvoiceCard(
    item: StatementInvoiceItem,
    onClick: () -> Unit,
) {
    val invoice = item.invoice
    val transaction = item.transaction
    val isCredit = transaction.direction == FinancialTransactionDirection.INCOME
    val semantic = MaterialTheme.financeColors
    val overdue = invoice.status == CreditCardInvoiceStatus.OVERDUE
    val paid = invoice.status == CreditCardInvoiceStatus.PAID
    val statusForeground = when {
        overdue -> MaterialTheme.colorScheme.onErrorContainer
        paid -> semantic.onRealizedContainer
        else -> semantic.onPendingContainer
    }
    val statusBackground = when {
        overdue -> MaterialTheme.colorScheme.errorContainer
        paid -> semantic.realizedContainer
        else -> semantic.pendingContainer
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FinanceIconTile(
                    icon = Icons.Rounded.CreditCard,
                    contentDescription = null,
                )
                Spacer(Modifier.width(FinanceSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        transaction.description,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FinanceStatusPill(
                        text = invoice.status.displayName,
                        foreground = statusForeground,
                        background = statusBackground,
                        icon = when {
                            overdue -> Icons.Rounded.WarningAmber
                            paid -> Icons.Rounded.CheckCircle
                            else -> Icons.Rounded.Schedule
                        },
                    )
                }
                Spacer(Modifier.width(FinanceSpacing.sm))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (isCredit) "+ " else "− ") +
                            formatCurrency(transaction.amount),
                        color = if (isCredit) semantic.income else semantic.expense,
                        style = FinanceTextStyles.moneyMedium,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = "Abrir detalhes da fatura",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.md),
            ) {
                Text(
                    "Vence ${invoice.dueDate?.format(SHORT_DATE_FORMATTER) ?: "sem data"}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${invoice.transactionCount} compras",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (invoice.paidAmount.signum() > 0 && invoice.total.signum() != 0) {
                val paidFraction = (
                    invoice.paidAmount.toFloat() / invoice.total.abs().toFloat()
                ).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { paidFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = semantic.realized,
                    trackColor = semantic.realizedContainer,
                )
                Text(
                    "Pago ${formatCurrency(invoice.paidAmount.toPlainString())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
