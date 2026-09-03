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
internal fun MonthlyBudgetScreen(
    store: DiagnosticStore,
) {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var refresh by remember { mutableIntStateOf(0) }
    var editingCategory by remember { mutableStateOf<CategoryChoice?>(null) }
    var editingTotal by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val budgets = remember(selectedMonth, refresh) { store.monthlyBudgets(selectedMonth) }
    val transactions = remember(refresh) { granularTransactions(store) }
    val progress = remember(selectedMonth, budgets, transactions) {
        MonthlyBudgetCalculator.calculate(selectedMonth, budgets, transactions)
    }
    val categorySpending = remember(selectedMonth, transactions) {
        MonthlyBudgetCalculator.spendingByCategory(selectedMonth, transactions)
    }
    val totalProgress = progress.firstOrNull { it.category == null }
    val categoryProgress = progress.filter { it.category != null }
    val expenseCategories = TransactionCategory.availableFor(
        FinancialTransactionDirection.EXPENSE,
    )
    val configuredKeys = categoryProgress.mapNotNull { it.categoryKey }.toSet()
    val customExpenseCategories = transactions
        .asSequence()
        .filter { it.direction == FinancialTransactionDirection.EXPENSE }
        .mapNotNull { it.customCategory?.takeIf(String::isNotBlank) }
        .distinct()
        .sorted()
        .toList()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Orçamento mensal") },
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
            item {
                MonthSelector(
                    selectedMonth = selectedMonth,
                    onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                    onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
                    Button(
                        onClick = { editingTotal = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (totalProgress == null) Icons.Rounded.Add else Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Text(if (totalProgress == null) "Limite total" else "Editar total")
                    }
                    OutlinedButton(
                        onClick = {
                            val copied = store.copyMonthlyBudgets(
                                selectedMonth.minusMonths(1), selectedMonth,
                            )
                            message = if (copied > 0) {
                                "$copied orçamento(s) copiado(s) do mês anterior."
                            } else "O mês anterior não possui orçamento."
                            if (copied > 0) refresh++
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Text("Copiar anterior")
                    }
                }
            }
            message?.let { value ->
                item {
                    FinanceNoticeCard(
                        icon = Icons.Rounded.Info,
                        title = "Orçamento atualizado",
                        description = value,
                        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                        background = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }
            totalProgress?.let { item ->
                item(key = "budget-total") {
                    BudgetProgressCard(item, onEdit = { editingTotal = true })
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(top = FinanceSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs),
                ) {
                    Text(
                        "Gastos por categoria",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Participação no total gasto no mês, com ou sem limite definido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (categorySpending.isEmpty()) {
                item(key = "category-spending-empty") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            "Nenhum gasto registrado neste mês.",
                            modifier = Modifier.padding(FinanceSpacing.md),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    categorySpending,
                    key = { _, spending -> "spending-${spending.categoryKey}" },
                ) { index, spending ->
                    CategorySpendingRow(rank = index + 1, spending = spending)
                }
            }
            if (categoryProgress.isNotEmpty()) {
                item {
                    Text(
                        "Limites por categoria",
                        modifier = Modifier.padding(top = FinanceSpacing.sm),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(categoryProgress, key = { it.categoryKey!! }) { item ->
                    BudgetProgressCard(
                        item,
                        onEdit = {
                            editingCategory = CategoryChoice(
                                category = item.category ?: TransactionCategory.OTHER_EXPENSE,
                                customCategory = item.customCategory,
                            )
                        },
                    )
                }
            }
            val missing = expenseCategories.filterNot { it.name in configuredKeys }
            val missingCustom = customExpenseCategories.filterNot {
                "CUSTOM:$it" in configuredKeys
            }
            if (missing.isNotEmpty() || missingCustom.isNotEmpty()) {
                item {
                    Text(
                        "Adicionar categoria",
                        modifier = Modifier.padding(top = FinanceSpacing.sm),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(missing, key = { "missing-${it.name}" }) { category ->
                    OutlinedButton(
                        onClick = { editingCategory = CategoryChoice(category) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            category.financeIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Text("Definir limite para ${category.displayName}")
                    }
                }
                items(missingCustom, key = { "missing-custom-$it" }) { name ->
                    OutlinedButton(
                        onClick = {
                            editingCategory = CategoryChoice(
                                TransactionCategory.OTHER_EXPENSE,
                                customCategory = name,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Rounded.Category,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Text("Definir limite para $name")
                    }
                }
            }
            item { Spacer(Modifier.height(FinanceSpacing.lg)) }
        }
    }

    if (editingTotal || editingCategory != null) {
        val choice = editingCategory
        val category = choice?.category
        val customCategory = choice?.customCategory
        val existing = budgets.firstOrNull {
            it.category == category && it.customCategory == customCategory
        }?.amount
        val includedTransactions = transactions.filter { transaction ->
            transaction.direction == FinancialTransactionDirection.EXPENSE &&
                transactionEffectiveDate(transaction)?.let { YearMonth.from(it) } == selectedMonth &&
                if (choice == null) true
                else if (customCategory != null) transaction.customCategory == customCategory
                else transaction.customCategory == null && transaction.category == category
        }
        BudgetEditDialog(
            title = customCategory ?: category?.displayName ?: "Limite total do mês",
            initialAmount = existing?.toPlainString().orEmpty(),
            transactions = includedTransactions,
            onDismiss = { editingTotal = false; editingCategory = null },
            onSave = { amount ->
                if (store.saveMonthlyBudget(
                        selectedMonth, category, amount, customCategory,
                    )) {
                    editingTotal = false
                    editingCategory = null
                    refresh++
                }
            },
            onDelete = existing?.let {
                {
                    store.deleteMonthlyBudget(selectedMonth, category, customCategory)
                    editingTotal = false
                    editingCategory = null
                    refresh++
                }
            },
        )
    }
}

@Composable
private fun CategorySpendingRow(
    rank: Int,
    spending: MonthlyCategorySpending,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(FinanceSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                rank.toString(),
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(FinanceSpacing.xs))
            FinanceIconTile(
                icon = spending.category.financeIcon(),
                contentDescription = null,
            )
            Spacer(Modifier.width(FinanceSpacing.sm))
            Text(
                spending.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(FinanceSpacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatCurrency(spending.amount.toPlainString()),
                    style = FinanceTextStyles.moneyMedium,
                    color = MaterialTheme.financeColors.expense,
                )
                Text(
                    "${spending.sharePercent}% do total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BudgetProgressCard(
    progress: MonthlyBudgetProgress,
    onEdit: () -> Unit,
) {
    val overBudget = progress.remaining.signum() < 0
    val nearLimit = !overBudget && progress.usagePercent >= 80
    val semantic = MaterialTheme.financeColors
    val progressColor = when {
        overBudget -> MaterialTheme.colorScheme.error
        nearLimit -> semantic.pending
        else -> MaterialTheme.colorScheme.primary
    }
    val stateForeground = when {
        overBudget -> MaterialTheme.colorScheme.onErrorContainer
        nearLimit -> semantic.onPendingContainer
        else -> semantic.onRealizedContainer
    }
    val stateBackground = when {
        overBudget -> MaterialTheme.colorScheme.errorContainer
        nearLimit -> semantic.pendingContainer
        else -> semantic.realizedContainer
    }
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(FinanceSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FinanceIconTile(
                    icon = progress.category?.financeIcon() ?: Icons.Rounded.DonutLarge,
                    contentDescription = null,
                )
                Spacer(Modifier.width(FinanceSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        progress.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Limite ${formatCurrency(progress.limit.toPlainString())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${progress.usagePercent}%",
                    style = FinanceTextStyles.moneyMedium,
                    color = progressColor,
                )
            }
            LinearProgressIndicator(
                progress = { (progress.usagePercent.coerceAtMost(100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = progressColor,
                trackColor = stateBackground,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.md),
            ) {
                SummaryValue(
                    label = "Realizado",
                    amount = progress.realized.toPlainString(),
                    color = semantic.expense,
                    modifier = Modifier.weight(1f),
                )
                SummaryValue(
                    label = "Pendente",
                    amount = progress.pending.toPlainString(),
                    color = semantic.pending,
                    modifier = Modifier.weight(1f),
                )
            }
            FinanceStatusPill(
                text = if (overBudget) {
                    "Excedente previsto: ${formatCurrency(progress.remaining.abs().toPlainString())}"
                } else {
                    "Disponível previsto: ${formatCurrency(progress.remaining.toPlainString())}"
                },
                foreground = stateForeground,
                background = stateBackground,
                icon = if (overBudget) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
            )
        }
    }
}

@Composable
private fun BudgetEditDialog(
    title: String,
    initialAmount: String,
    transactions: List<FinancialTransactionRecord>,
    onDismiss: () -> Unit,
    onSave: (java.math.BigDecimal) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var amount by remember(title, initialAmount) { mutableStateOf(initialAmount) }
    val semantic = MaterialTheme.financeColors
    val parsed = remember(amount) {
        if (',' in amount) amount.replace(".", "").replace(',', '.').toBigDecimalOrNull()
        else amount.toBigDecimalOrNull()
    }
    val realized = transactions
        .filter { it.status == TransactionStatus.REALIZED }
        .sumOf { it.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO }
    val pending = transactions
        .filter { it.status == TransactionStatus.PENDING }
        .sumOf { it.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
            ) {
                item {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Limite mensal") },
                        prefix = { Text("R$ ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Insights,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(FinanceSpacing.xs))
                                Text(
                                    "Resumo do mês",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
                            ) {
                                SummaryValue(
                                    label = "Realizado",
                                    amount = realized.toPlainString(),
                                    color = semantic.expense,
                                    modifier = Modifier.weight(1f),
                                )
                                SummaryValue(
                                    label = "Pendente",
                                    amount = pending.toPlainString(),
                                    color = semantic.pending,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SummaryValue(
                                label = "Total previsto",
                                amount = (realized + pending).toPlainString(),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Movimentações incluídas (${transactions.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (transactions.isEmpty()) {
                    item {
                        FinanceEmptyState(
                            icon = Icons.Rounded.ReceiptLong,
                            title = "Nenhuma movimentação",
                            description = "Esta categoria não possui movimentações no mês.",
                        )
                    }
                } else {
                    items(transactions, key = { "budget-transaction-${it.id}" }) { transaction ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = FinanceSpacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FinanceIconTile(
                                icon = transaction.category.financeIcon(),
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    transaction.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                FinanceStatusPill(
                                    text = if (
                                        transaction.status == TransactionStatus.REALIZED
                                    ) "Realizado" else "Pendente",
                                    foreground = if (
                                        transaction.status == TransactionStatus.REALIZED
                                    ) semantic.onRealizedContainer
                                    else semantic.onPendingContainer,
                                    background = if (
                                        transaction.status == TransactionStatus.REALIZED
                                    ) semantic.realizedContainer
                                    else semantic.pendingContainer,
                                )
                            }
                            Text(
                                formatCurrency(transaction.amount),
                                style = FinanceTextStyles.moneyMedium,
                                color = semantic.expense,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSave) },
                enabled = parsed?.signum() == 1,
            ) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text("Remover", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
