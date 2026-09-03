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
import br.com.assistentefinanceiro.ui.viewmodels.CardInvoicesViewModel
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
internal fun CardInvoicesScreen(
    repository: FinancialRepository,
    account: FinancialAccountRecord,
    onBack: () -> Unit,
) {
    val screenViewModel: CardInvoicesViewModel = viewModel(
        key = "card-invoices-${account.id}",
        factory = ScreenViewModelFactory { CardInvoicesViewModel(repository, account) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val selectedMonth = uiState.selectedMonth
    val visibleInvoice = uiState.visibleInvoice
    uiState.selectedInvoice?.let { invoice ->
        InvoiceDetailScreen(
            repository = repository,
            account = account,
            invoice = invoice,
            onBack = { screenViewModel.selectInvoice(null) },
            onPayment = screenViewModel::recordPayment,
            onDeletePayment = screenViewModel::deletePayment,
            onInvoiceAdjustment = screenViewModel::adjustInvoice,
            onTransactionChanged = screenViewModel::onTransactionChanged,
        )
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Faturas")
                        Text(
                            account.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Voltar para contas",
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
                MonthSelector(
                    selectedMonth = selectedMonth,
                    onPrevious = screenViewModel::showPreviousMonth,
                    onNext = screenViewModel::showNextMonth,
                )
            }
            if (visibleInvoice == null) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.CreditCard,
                        title = "Nenhuma fatura em ${formatMonth(selectedMonth)}",
                        description = "As compras vinculadas ao cartão aparecerão aqui.",
                    )
                }
            }
            visibleInvoice?.let { invoice ->
            item(key = "invoice-${invoice.id}") {
                val semantic = MaterialTheme.financeColors
                val overdue = invoice.status == CreditCardInvoiceStatus.OVERDUE
                val paid = invoice.status == CreditCardInvoiceStatus.PAID
                Surface(
                    onClick = { screenViewModel.selectInvoice(invoice) },
                    modifier = Modifier.fillMaxWidth(),
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val referenceMonth = invoice.dueDate?.let(YearMonth::from)
                                ?: invoice.closingPeriod
                            FinanceIconTile(
                                icon = Icons.Rounded.CreditCard,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    formatMonth(referenceMonth),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${invoice.transactionCount} movimentações",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = "Abrir fatura",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                            Text(
                                "Vence " + (
                                    invoice.dueDate?.format(SHORT_DATE_FORMATTER)
                                        ?: "sem data"
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Fecha em ${invoice.closingDate.format(SHORT_DATE_FORMATTER)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            }
        }
    }
}
