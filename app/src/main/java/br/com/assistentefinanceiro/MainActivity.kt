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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.notifications.*
import br.com.assistentefinanceiro.importing.MobillsImportAnalyzer
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.importing.SimpleXlsxReader
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppScreen {
    STATEMENT,
    DIAGNOSTIC,
}

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
                        onOpenDiagnostic = { screen = AppScreen.DIAGNOSTIC },
                    )
                    AppScreen.DIAGNOSTIC -> DiagnosticScreen(
                        store = store,
                        preferences = preferences,
                        onOpenStatement = { screen = AppScreen.STATEMENT },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MonthlyStatementScreen(
        store: DiagnosticStore,
        onOpenDiagnostic: () -> Unit,
    ) {
        var refresh by remember { mutableIntStateOf(0) }
        var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
        val transactions = remember(refresh) { store.recentTransactions(limit = 10_000) }
        val statement = remember(transactions, selectedMonth) {
            MonthlyStatementCalculator.calculate(selectedMonth, transactions)
        }
        var editingTransaction by remember {
            mutableStateOf<FinancialTransactionRecord?>(null)
        }
        var importPreview by remember { mutableStateOf<MobillsImportPreview?>(null) }
        var includePossibleDuplicates by remember { mutableStateOf(false) }
        var importMessage by remember { mutableStateOf<String?>(null) }
        var importError by remember { mutableStateOf<String?>(null) }
        var readingImport by remember { mutableStateOf(false) }
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
                                        today = LocalDate.now(),
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
                        TextButton(onClick = onOpenDiagnostic) {
                            Text("Diagnóstico")
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
                item { StatementSummary(statement) }
                if (statement.categoryExpenses.isNotEmpty()) {
                    item { ExpenseByCategoryCard(statement.categoryExpenses) }
                }
                item {
                    OutlinedButton(
                        onClick = { refresh++ },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Atualizar movimentações")
                    }
                }
                if (statement.groups.isEmpty()) {
                    item {
                        Card {
                            Text(
                                text = "Nenhuma movimentação reconhecida neste mês.",
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                statement.groups.forEach { group ->
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
                        TransactionCard(
                            transaction = transaction,
                            onClick = { editingTransaction = transaction },
                        )
                    }
                }
            }
        }

        editingTransaction?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                onDismiss = { editingTransaction = null },
                onSave = { description, category, applyToFuture ->
                    if (
                        store.updateTransactionDetails(
                            transactionId = transaction.id,
                            description = description,
                            category = category,
                            applyToFuture = applyToFuture,
                        )
                    ) {
                        editingTransaction = null
                        refresh++
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
                        Text("Prontos: ${preview.readyCount}")
                        Text("Planejados: ${preview.plannedCount}")
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
    private fun MonthSelector(
        selectedMonth: YearMonth,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
    ) {
        Card {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
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
    private fun StatementSummary(statement: MonthlyStatement) {
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
                Text("Resultado do mês", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = formatCurrency(statement.balance.toPlainString()),
                    color = balanceColor,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Com base nas notificações reconhecidas",
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
                            } + if (transaction.status == TransactionStatus.PLANNED) {
                                " · planejada"
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatTime(transaction.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    @Composable
    private fun EditTransactionDialog(
        transaction: FinancialTransactionRecord,
        onDismiss: () -> Unit,
        onSave: (String, TransactionCategory, Boolean) -> Unit,
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
                        onSave(description, selectedCategory, applyToFuture)
                    },
                    enabled = description.isNotBlank(),
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            },
        )
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
                                    FinancialTransactionType.IMPORTED_INCOME ->
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
        const val DESCRIPTION_MAX_LENGTH = 80
    }
}
