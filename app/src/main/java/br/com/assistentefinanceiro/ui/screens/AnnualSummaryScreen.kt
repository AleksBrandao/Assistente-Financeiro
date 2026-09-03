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
import br.com.assistentefinanceiro.ui.viewmodels.AnnualSummaryViewModel
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
internal fun AnnualSummaryScreen(
    store: DiagnosticStore,
    onBack: () -> Unit,
) {
    val screenViewModel: AnnualSummaryViewModel = viewModel(
        factory = ScreenViewModelFactory { AnnualSummaryViewModel(store) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val selectedYear = uiState.selectedYear
    val rows = uiState.rows
    val projectedBalances = uiState.projectedBalances
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Resumo anual") },
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
                            .padding(FinanceSpacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = screenViewModel::showPreviousYear) {
                            Icon(
                                Icons.Rounded.ChevronLeft,
                                contentDescription = "Ano anterior",
                            )
                        }
                        Text(
                            selectedYear.toString(), Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        IconButton(onClick = screenViewModel::showNextYear) {
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = "Próximo ano",
                            )
                        }
                    }
                }
            }
            when (projectedBalances) {
                LoadState.Loading -> item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LoadState.Failed -> item {
                    Text(
                        text = "Não foi possível calcular os saldos projetados.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is LoadState.Ready -> Unit
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(FinanceSpacing.sm),
                    ) {
                        Row {
                            AnnualSummaryCell("Mês", header = true, firstColumn = true)
                            AnnualSummaryCell("Entradas", header = true)
                            AnnualSummaryCell("Saídas", header = true)
                            AnnualSummaryCell("Resultado", header = true)
                            AnnualSummaryCell("Saldo projetado", header = true)
                        }
                        HorizontalDivider()
                        rows.forEachIndexed { index, (period, statement) ->
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
                                when (val balances = projectedBalances) {
                                    LoadState.Loading -> AnnualSummaryCell("…")
                                    LoadState.Failed -> AnnualSummaryCell(
                                        text = "Indisponível",
                                        valueColor = MaterialTheme.colorScheme.error,
                                    )
                                    is LoadState.Ready -> {
                                        val projectedBalance =
                                            balances.value[period.atEndOfMonth()]
                                        if (projectedBalance == null) {
                                            AnnualSummaryCell(
                                                text = "Indisponível",
                                                valueColor = MaterialTheme.colorScheme.error,
                                            )
                                        } else {
                                            AnnualSummaryCell(
                                                formatCurrency(
                                                    projectedBalance.toPlainString()
                                                ),
                                                valueColor = financialValueColor(
                                                    projectedBalance.signum()
                                                ),
                                            )
                                        }
                                    }
                                }
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
            .padding(
                horizontal = FinanceSpacing.xs,
                vertical = FinanceSpacing.sm,
            ),
        style = if (header) MaterialTheme.typography.labelLarge
        else MaterialTheme.typography.bodySmall,
        textAlign = if (firstColumn) TextAlign.Start else TextAlign.End,
        color = valueColor,
    )
}

@Composable
private fun financialValueColor(sign: Int): Color = when {
    sign > 0 -> MaterialTheme.financeColors.income
    sign < 0 -> MaterialTheme.financeColors.expense
    else -> Color.Unspecified
}
