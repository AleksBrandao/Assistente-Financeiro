package br.com.assistentefinanceiro.ui.screens

import br.com.assistentefinanceiro.data.FinancialRepository
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
import br.com.assistentefinanceiro.ui.viewmodels.InvoiceDetailViewModel
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
internal fun InvoiceDetailScreen(
    repository: FinancialRepository,
    account: FinancialAccountRecord,
    invoice: CreditCardInvoiceRecord,
    onBack: () -> Unit,
    onPayment: (java.math.BigDecimal, LocalDate, Long?) -> Boolean,
    onDeletePayment: (Long) -> Boolean,
    onInvoiceAdjustment: (java.math.BigDecimal) -> Boolean,
    onTransactionChanged: () -> Unit,
) {
    val screenViewModel: InvoiceDetailViewModel = viewModel(
        key = "invoice-detail-${invoice.id}-${invoice.total}-${invoice.paidAmount}",
        factory = ScreenViewModelFactory { InvoiceDetailViewModel(repository, invoice) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val transactions = uiState.transactions
    val payments = uiState.payments
    val bankAccounts = uiState.bankAccounts
    val referenceMonth = uiState.referenceMonth
    val semantic = MaterialTheme.financeColors
    val overdue = invoice.status == CreditCardInvoiceStatus.OVERDUE
    val paid = invoice.status == CreditCardInvoiceStatus.PAID
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(formatMonth(referenceMonth)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Voltar para faturas",
                        )
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
                                icon = Icons.Rounded.CreditCard,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    account.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${transactions.size} movimentações",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FinanceStatusPill(
                                text = invoice.status.displayName,
                                foreground = when {
                                    overdue -> MaterialTheme.colorScheme.onErrorContainer
                                    paid -> semantic.onRealizedContainer
                                    else -> semantic.onPendingContainer
                                },
                                background = when {
                                    overdue -> MaterialTheme.colorScheme.errorContainer
                                    paid -> semantic.realizedContainer
                                    else -> semantic.pendingContainer
                                },
                                icon = when {
                                    overdue -> Icons.Rounded.WarningAmber
                                    paid -> Icons.Rounded.CheckCircle
                                    else -> Icons.Rounded.Schedule
                                },
                            )
                        }
                        Text(
                            formatCurrency(invoice.total.toPlainString()),
                            color = if (invoice.total.signum() < 0) {
                                semantic.income
                            } else MaterialTheme.colorScheme.onSurface,
                            style = FinanceTextStyles.moneyHero,
                            maxLines = 1,
                        )
                        if (invoice.adjustmentAmount.signum() != 0) {
                            FinanceNoticeCard(
                                icon = Icons.Rounded.Tune,
                                title = "Ajuste aplicado",
                                description = (
                                    if (invoice.adjustmentAmount.signum() > 0) {
                                        "Débito de ajuste: "
                                    } else {
                                        "Crédito de ajuste: "
                                    }
                                    ) + formatCurrency(
                                    invoice.adjustmentAmount.abs().toPlainString()
                                ),
                                foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                                background = MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.md),
                        ) {
                            SummaryValue(
                                label = "Pago",
                                amount = invoice.paidAmount.toPlainString(),
                                color = semantic.income,
                                modifier = Modifier.weight(1f),
                            )
                            SummaryValue(
                                label = "Em aberto",
                                amount = invoice.outstandingAmount.toPlainString(),
                                color = if (invoice.outstandingAmount.signum() > 0) {
                                    semantic.expense
                                } else semantic.realized,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "Fecha em ${invoice.closingDate.format(SHORT_DATE_FORMATTER)}" +
                                (invoice.dueDate?.let {
                                    " · vence em ${it.format(SHORT_DATE_FORMATTER)}"
                                } ?: " · vencimento não informado"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                        ) {
                            OutlinedButton(
                                onClick = { screenViewModel.setAdjustingInvoice(true) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Rounded.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(FinanceSpacing.xs))
                                Text("Ajustar")
                            }
                            Button(
                                onClick = { screenViewModel.setAddingPayment(true) },
                                enabled = invoice.outstandingAmount.signum() > 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Rounded.Payments,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(FinanceSpacing.xs))
                                Text("Pagar")
                            }
                        }
                    }
                }
            }
            if (payments.isNotEmpty()) {
                item {
                    Text(
                        "Pagamentos",
                        modifier = Modifier.padding(top = FinanceSpacing.sm),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(payments, key = { "invoice-payment-${it.id}" }) { payment ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FinanceIconTile(
                                icon = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                containerColor = semantic.realizedContainer,
                                iconColor = semantic.onRealizedContainer,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    formatCurrency(payment.amount.toPlainString()),
                                    color = semantic.income,
                                    style = FinanceTextStyles.moneyMedium,
                                )
                                Text(
                                    payment.paidAt.format(SHORT_DATE_FORMATTER),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                payment.sourceAccountName?.let { name ->
                                    Text(
                                        "Pago com $name",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { screenViewModel.requestDeletePayment(payment) },
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Excluir pagamento",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Lançamentos",
                    modifier = Modifier.padding(top = FinanceSpacing.sm),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(transactions, key = { "invoice-transaction-${it.id}" }) { transaction ->
                val occurredAt = runCatching {
                    LocalDateTime.parse(transaction.occurredAt)
                }.getOrNull()
                val dateLabel = occurredAt?.toLocalDate()?.format(SHORT_DATE_FORMATTER)
                    ?.let { date ->
                        if (transaction.origin == TransactionOrigin.MOBILLS) {
                            "Referência $date"
                        } else date
                    }
                TransactionCard(
                    transaction = transaction,
                    onClick = { screenViewModel.editTransaction(transaction) },
                    footerText = listOfNotNull(
                        dateLabel,
                        if (transaction.origin == TransactionOrigin.MOBILLS) {
                            "Mobills"
                        } else "Notificação",
                    ).joinToString(" · "),
                )
            }
        }
    }

    if (uiState.adjustingInvoice) {
        val officialValue = uiState.officialValue
        AlertDialog(
            onDismissRequest = { screenViewModel.setAdjustingInvoice(false) },
            title = { Text("Ajustar valor da fatura") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm)) {
                    Text("Compras: ${formatCurrency(invoice.baseTotal.toPlainString())}")
                    OutlinedTextField(
                        value = uiState.officialTotal,
                        onValueChange = screenViewModel::setOfficialTotal,
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
                        screenViewModel.submitAdjustment(onInvoiceAdjustment)
                    },
                    enabled = officialValue?.signum()?.let { it >= 0 } == true,
                ) { Text("Salvar ajuste") }
            },
            dismissButton = {
                TextButton(onClick = { screenViewModel.setAdjustingInvoice(false) }) {
                    Text("Cancelar")
                }
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
                    )) onTransactionChanged()
            },
        )
    }
    if (uiState.addingPayment) {
        val parsedAmount = uiState.parsedPaymentAmount
        val parsedDate = uiState.parsedPaymentDate
        AlertDialog(
            onDismissRequest = { screenViewModel.setAddingPayment(false) },
            title = { Text("Registrar pagamento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm)) {
                    Text(
                        "Saldo da fatura: " +
                            formatCurrency(invoice.outstandingAmount.toPlainString())
                    )
                    OutlinedTextField(
                        value = uiState.paymentAmount,
                        onValueChange = screenViewModel::setPaymentAmount,
                        label = { Text("Valor pago") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    if (bankAccounts.isNotEmpty()) {
                        Box {
                            OutlinedButton(
                                onClick = {
                                    screenViewModel.setSourceAccountMenuExpanded(true)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    bankAccounts.firstOrNull {
                                        it.id == uiState.sourceAccountId
                                    }?.name
                                        ?: "Selecionar conta de pagamento"
                                )
                            }
                            DropdownMenu(
                                expanded = uiState.sourceAccountMenuExpanded,
                                onDismissRequest = {
                                    screenViewModel.setSourceAccountMenuExpanded(false)
                                },
                            ) {
                                bankAccounts.forEach { bankAccount ->
                                    DropdownMenuItem(
                                        text = { Text(bankAccount.name) },
                                        onClick = {
                                            screenViewModel.selectSourceAccount(bankAccount.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    DatePickerField(
                        uiState.paymentDate,
                        "Data do pagamento",
                        onValueChange = screenViewModel::setPaymentDate,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        screenViewModel.submitPayment(onPayment)
                    },
                    enabled = parsedAmount != null && parsedAmount.signum() > 0 &&
                        parsedAmount <= invoice.outstandingAmount && parsedDate != null &&
                        (bankAccounts.isEmpty() || uiState.sourceAccountId != null),
                ) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { screenViewModel.setAddingPayment(false) }) {
                    Text("Cancelar")
                }
            },
        )
    }
    uiState.deletingPayment?.let { payment ->
        AlertDialog(
            onDismissRequest = { screenViewModel.requestDeletePayment(null) },
            title = { Text("Excluir pagamento?") },
            text = {
                Text(
                    formatCurrency(payment.amount.toPlainString()) + " em " +
                        payment.paidAt.format(SHORT_DATE_FORMATTER)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    screenViewModel.confirmDeletePayment(onDeletePayment)
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { screenViewModel.requestDeletePayment(null) }) {
                    Text("Cancelar")
                }
            },
        )
    }
}
