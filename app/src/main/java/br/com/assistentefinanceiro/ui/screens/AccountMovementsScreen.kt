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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import br.com.assistentefinanceiro.ui.viewmodels.AccountMovementsViewModel
import br.com.assistentefinanceiro.ui.viewmodels.ScreenViewModelFactory
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
internal fun AccountMovementsScreen(
    store: DiagnosticStore,
    account: FinancialAccountRecord,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val screenViewModel: AccountMovementsViewModel = viewModel(
        key = "account-movements-${account.id}",
        factory = ScreenViewModelFactory { AccountMovementsViewModel(store, account) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val selectedMonth = uiState.selectedMonth
    val balance = uiState.balance
    val visibleLedgerItems = uiState.visibleLedgerItems
    val semantic = MaterialTheme.financeColors
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(account.name) },
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
                .padding(padding)
                .padding(horizontal = FinanceSpacing.md),
            contentPadding = PaddingValues(vertical = FinanceSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FinanceSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FinanceIconTile(
                                icon = Icons.Rounded.AccountBalance,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Text(
                                "Saldo atual",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            formatCurrency(balance.realizedBalance.toPlainString()),
                            style = FinanceTextStyles.moneyHero,
                            color = if (balance.realizedBalance.signum() < 0) {
                                semantic.expense
                            } else semantic.income,
                            maxLines = 1,
                        )
                        SummaryValue(
                            label = "Saldo previsto",
                            amount = balance.projectedBalance.toPlainString(),
                            color = if (balance.projectedBalance.signum() < 0) {
                                semantic.expense
                            } else semantic.income,
                        )
                        account.openingBalanceDate?.let { date ->
                            Text(
                                "Saldo inicial em ${date.format(SHORT_DATE_FORMATTER)}: " +
                                    formatCurrency(account.openingBalance.toPlainString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { screenViewModel.setAddingTransaction(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Adicionar receita ou despesa")
                }
            }
            item {
                MonthSelector(
                    selectedMonth = selectedMonth,
                    onPrevious = screenViewModel::showPreviousMonth,
                    onNext = screenViewModel::showNextMonth,
                )
            }
            if (visibleLedgerItems.isEmpty()) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.ReceiptLong,
                        title = "Nenhuma movimentação",
                        description = "Não há receitas ou despesas nesta conta em " +
                            "${formatMonth(selectedMonth)}.",
                        actionLabel = "Adicionar movimentação",
                        onAction = { screenViewModel.setAddingTransaction(true) },
                    )
                }
            }
            items(visibleLedgerItems, key = { it.key }) { ledgerItem ->
                val isExpense = ledgerItem.direction ==
                    FinancialTransactionDirection.EXPENSE
                val valueColor = if (isExpense) semantic.expense else semantic.income
                val valueContainer = if (isExpense) {
                    semantic.expenseContainer
                } else semantic.incomeContainer
                val itemIcon = ledgerItem.transaction?.category?.financeIcon()
                    ?: if (ledgerItem.movement?.type == AccountMovementType.TRANSFER) {
                        Icons.Rounded.SwapHoriz
                    } else Icons.Rounded.ReceiptLong
                Surface(
                    onClick = {
                        ledgerItem.transaction?.takeIf {
                            it.origin == TransactionOrigin.MANUAL
                        }?.let(screenViewModel::editTransaction)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FinanceSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FinanceIconTile(
                            icon = itemIcon,
                            contentDescription = null,
                            containerColor = valueContainer,
                            iconColor = valueColor,
                        )
                        Spacer(Modifier.width(FinanceSpacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                ledgerItem.description,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                ledgerItem.occurredAt.toLocalDate().format(SHORT_DATE_FORMATTER) +
                                    (ledgerItem.detail.takeIf { it.isNotBlank() }
                                        ?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                (if (isExpense) "− " else "+ ") +
                                    formatCurrency(ledgerItem.amount.toPlainString()),
                                color = valueColor,
                                style = FinanceTextStyles.moneyMedium,
                                maxLines = 1,
                            )
                            if (ledgerItem.movement?.type == AccountMovementType.TRANSFER) {
                                IconButton(
                                    onClick = {
                                        screenViewModel.requestDeleteTransfer(ledgerItem.movement)
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Excluir transferência",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (uiState.addingTransaction) {
        ManualTransactionDialog(
            onDismiss = { screenViewModel.setAddingTransaction(false) },
            onSave = { direction, amount, date, description, status, occurrences ->
                if (screenViewModel.recordManualTransaction(
                        direction, amount, date, description, status, occurrences,
                    )) onChanged()
            },
        )
    }
    uiState.editingTransaction?.let { transaction ->
        EditTransactionDialog(
            transaction = transaction,
            customCategories = uiState.customCategories[transaction.direction].orEmpty(),
            onDismiss = { screenViewModel.editTransaction(null) },
            onSave = { description, category, customCategory, subcategory, status, amount, dueDate, plannedDate, paidAt, applyToFuture, scope ->
                if (screenViewModel.saveTransaction(
                        description, category, customCategory, subcategory, status, amount,
                        dueDate, plannedDate, paidAt, applyToFuture, scope,
                    )) onChanged()
            },
            onDelete = { scope ->
                screenViewModel.requestDelete(transaction, scope)
            },
        )
    }
    uiState.deletingTransaction?.let { transaction ->
        AlertDialog(
            onDismissRequest = screenViewModel::dismissDeleteTransaction,
            title = { Text("Excluir movimentação manual?") },
            text = { Text(transaction.description) },
            confirmButton = {
                TextButton(onClick = {
                    if (screenViewModel.confirmDeleteTransaction()) onChanged()
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = screenViewModel::dismissDeleteTransaction) {
                    Text("Cancelar")
                }
            },
        )
    }
    uiState.deletingTransfer?.let { movement ->
        AlertDialog(
            onDismissRequest = { screenViewModel.requestDeleteTransfer(null) },
            title = { Text("Excluir transferência?") },
            text = { Text("Os lançamentos nas duas contas serão removidos.") },
            confirmButton = {
                TextButton(onClick = {
                    if (screenViewModel.confirmDeleteTransfer()) onChanged()
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { screenViewModel.requestDeleteTransfer(null) }) {
                    Text("Cancelar")
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm)) {
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
