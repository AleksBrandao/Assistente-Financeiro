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
import br.com.assistentefinanceiro.ui.viewmodels.AccountsViewModel
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
internal fun AccountsScreen(
    store: DiagnosticStore,
) {
    val screenViewModel: AccountsViewModel = viewModel(
        factory = ScreenViewModelFactory { AccountsViewModel(store) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val accounts = uiState.accounts
    val bankBalances = uiState.bankBalances

    uiState.viewingInvoicesFor?.let { account ->
        CardInvoicesScreen(
            store = store,
            account = account,
            onBack = screenViewModel::closeChildAndRefresh,
        )
        return
    }
    uiState.viewingMovementsFor?.let { account ->
        AccountMovementsScreen(
            store = store,
            account = account,
            onBack = screenViewModel::closeChildAndRefresh,
            onChanged = screenViewModel::refresh,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Contas e cartões") },
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
            if (bankBalances.isNotEmpty()) {
                item {
                    val realized = bankBalances.values.fold(java.math.BigDecimal.ZERO) {
                            total, balance -> total + balance.realizedBalance
                    }
                    val projected = bankBalances.values.fold(java.math.BigDecimal.ZERO) {
                            total, balance -> total + balance.projectedBalance
                    }
                    val semantic = MaterialTheme.financeColors
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
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
                                Text(
                                    "Saldo consolidado",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                formatCurrency(realized.toPlainString()),
                                style = FinanceTextStyles.moneyHero,
                                color = if (realized.signum() < 0) {
                                    semantic.expense
                                } else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            SummaryValue(
                                label = "Saldo previsto",
                                amount = projected.toPlainString(),
                                color = if (projected.signum() < 0) {
                                    semantic.expense
                                } else semantic.income,
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
                    Button(
                        onClick = { screenViewModel.setCreatingAccount(true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Text("Adicionar")
                    }
                    OutlinedButton(
                        onClick = { screenViewModel.setCreatingTransfer(true) },
                        enabled = accounts.count {
                            it.type == FinancialAccountType.BANK_ACCOUNT
                        } >= 2,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(FinanceSpacing.xs))
                        Text("Transferir")
                    }
                }
            }
            if (accounts.isEmpty()) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.AccountBalanceWallet,
                        title = "Nenhuma conta cadastrada",
                        description = "Adicione uma conta bancária ou cartão para começar.",
                        actionLabel = "Adicionar conta",
                        onAction = { screenViewModel.setCreatingAccount(true) },
                    )
                }
            }
            items(accounts, key = { "account-${it.id}" }) { account ->
                val balance = bankBalances[account]
                val semantic = MaterialTheme.financeColors
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FinanceIconTile(
                                icon = if (
                                    account.type == FinancialAccountType.CREDIT_CARD
                                ) Icons.Rounded.CreditCard else Icons.Rounded.AccountBalance,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    account.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    account.type.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (account.isDefault) {
                                FinanceStatusPill(
                                    text = "Padrão",
                                    foreground = semantic.onRealizedContainer,
                                    background = semantic.realizedContainer,
                                    icon = Icons.Rounded.CheckCircle,
                                )
                            }
                        }
                        if (account.type == FinancialAccountType.CREDIT_CARD) {
                            Text(
                                "Fechamento: ${account.closingDay ?: "não informado"} · " +
                                    "Vencimento: ${account.dueDay ?: "não informado"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (balance != null) {
                            Text(
                                formatCurrency(balance.realizedBalance.toPlainString()),
                                color = if (balance.realizedBalance.signum() < 0) {
                                    semantic.expense
                                } else semantic.income,
                                style = FinanceTextStyles.moneyLarge,
                                maxLines = 1,
                            )
                            if (balance.projectedBalance != balance.realizedBalance) {
                                Text(
                                    "Previsto: ${formatCurrency(balance.projectedBalance.toPlainString())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { screenViewModel.editAccount(account) }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(FinanceSpacing.xxs))
                                Text("Editar")
                            }
                            if (account.type == FinancialAccountType.CREDIT_CARD) {
                                TextButton(onClick = { screenViewModel.viewInvoices(account) }) {
                                    Icon(
                                        Icons.Rounded.ReceiptLong,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(FinanceSpacing.xxs))
                                    Text("Faturas")
                                }
                            } else {
                                TextButton(onClick = { screenViewModel.viewMovements(account) }) {
                                    Icon(
                                        Icons.Rounded.ListAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(FinanceSpacing.xxs))
                                    Text("Movimentações")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogAccount = uiState.editingAccount ?: if (uiState.creatingAccount) {
        FinancialAccountRecord(
            id = 0,
            name = "",
            type = FinancialAccountType.BANK_ACCOUNT,
        )
    } else null
    dialogAccount?.let { account ->
        EditAccountDialog(
            account = account,
            isNew = uiState.creatingAccount,
            onDismiss = {
                screenViewModel.editAccount(null)
                screenViewModel.setCreatingAccount(false)
            },
            onSave = { name, type, closingDay, dueDay, isDefault, cardIdentifiers,
                       openingBalance, openingBalanceDate ->
                screenViewModel.saveAccount(
                    account, name, type, closingDay, dueDay, isDefault, cardIdentifiers,
                    openingBalance, openingBalanceDate,
                )
            },
        )
    }
    if (uiState.creatingTransfer) {
        TransferDialog(
            accounts = accounts.filter { it.type == FinancialAccountType.BANK_ACCOUNT },
            onDismiss = { screenViewModel.setCreatingTransfer(false) },
            onSave = { source, destination, amount, date, description ->
                screenViewModel.recordTransfer(source, destination, amount, date, description)
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
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
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
