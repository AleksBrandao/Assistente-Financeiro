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
import br.com.assistentefinanceiro.ui.viewmodels.PlanningViewModel
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
internal fun PlanningScreen(
    store: DiagnosticStore,
) {
    val screenViewModel: PlanningViewModel = viewModel(
        factory = ScreenViewModelFactory { PlanningViewModel(store) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val horizonDays = uiState.horizonDays
    val visible = uiState.visibleItems
    val income = uiState.income
    val expense = uiState.expense
    val net = uiState.net
    val projectedBalance = uiState.projectedBalance
    val semantic = MaterialTheme.financeColors

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Planejamento") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                ) {
                    listOf(30, 60, 90).forEach { days ->
                        FilterChip(
                            selected = horizonDays == days,
                            onClick = { screenViewModel.setHorizonDays(days) },
                            label = { Text("$days dias") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
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
                                icon = Icons.Rounded.EventNote,
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
                                    "Próximos $horizonDays dias",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        when (val balance = projectedBalance) {
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
                                label = "Entradas pendentes",
                                amount = income.toPlainString(),
                                prefix = "+ ",
                                color = semantic.income,
                                modifier = Modifier.weight(1f),
                            )
                            SummaryValue(
                                label = "Saídas pendentes",
                                amount = expense.toPlainString(),
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Resultado do período",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    formatCurrency(net.toPlainString()),
                                    color = if (net.signum() < 0) {
                                        semantic.expense
                                    } else semantic.income,
                                    style = FinanceTextStyles.moneyMedium,
                                )
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.EventAvailable,
                        title = "Nenhuma pendência",
                        description = "Não há pagamentos ou recebimentos previstos nos " +
                            "próximos $horizonDays dias.",
                    )
                }
            }
            visible.groupBy { YearMonth.from(it.date) }.forEach { (month, monthItems) ->
                item(key = "planning-month-$month") {
                    Text(
                        formatMonth(month),
                        modifier = Modifier.padding(top = FinanceSpacing.sm),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(monthItems, key = { "planning-${it.transaction.id}" }) { item ->
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
                                icon = item.transaction.category.financeIcon(),
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.transaction.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.date.format(SHORT_DATE_FORMATTER) +
                                        (item.transaction.account?.let { " · $it" } ?: "") +
                                        (item.transaction.seriesIndex?.let { index ->
                                            " · $index/${item.transaction.seriesTotal}"
                                        } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val expenseItem = item.transaction.direction ==
                                FinancialTransactionDirection.EXPENSE
                            Text(
                                (if (expenseItem) "− " else "+ ") +
                                    formatCurrency(item.transaction.amount),
                                color = if (expenseItem) semantic.expense else semantic.income,
                                style = FinanceTextStyles.moneyMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
