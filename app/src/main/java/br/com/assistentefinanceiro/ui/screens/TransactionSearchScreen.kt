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
import br.com.assistentefinanceiro.ui.viewmodels.ScreenViewModelFactory
import br.com.assistentefinanceiro.ui.viewmodels.TransactionSearchViewModel
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
internal fun TransactionSearchScreen(
    store: DiagnosticStore,
    onBack: () -> Unit,
) {
    val screenViewModel: TransactionSearchViewModel = viewModel(
        factory = ScreenViewModelFactory { TransactionSearchViewModel(store) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Pesquisar movimentações") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Voltar para Mais",
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
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = screenViewModel::setQuery,
                    label = { Text("Nome ou descrição") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
                    Box(Modifier.weight(1f)) {
                        DatePickerField(
                            uiState.fromDate, "De", allowClear = true,
                            onValueChange = screenViewModel::setFromDate,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        DatePickerField(
                            uiState.toDate, "Até", allowClear = true,
                            onValueChange = screenViewModel::setToDate,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                ) {
                    FilterChip(
                        selected = uiState.statusFilter == null,
                        onClick = { screenViewModel.setStatusFilter(null) },
                        label = { Text("Todas") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = uiState.statusFilter == TransactionStatus.REALIZED,
                        onClick = {
                            screenViewModel.setStatusFilter(TransactionStatus.REALIZED)
                        },
                        label = { Text("Pagas") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = uiState.statusFilter == TransactionStatus.PENDING,
                        onClick = {
                            screenViewModel.setStatusFilter(TransactionStatus.PENDING)
                        },
                        label = { Text("Não pagas") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Text(
                    if (uiState.results.size == 1) {
                        "1 resultado"
                    } else "${uiState.results.size} resultados",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.results.isEmpty()) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = "Nenhuma movimentação encontrada",
                        description = "Revise o texto, as datas ou a situação selecionada.",
                        actionLabel = "Limpar busca",
                        onAction = screenViewModel::clearSearch,
                    )
                }
            }
            items(uiState.results, key = { "search-${it.id}" }) { transaction ->
                TransactionCard(
                    transaction = transaction,
                    onClick = { screenViewModel.editTransaction(transaction) },
                )
            }
        }
    }
    uiState.editingTransaction?.let { transaction ->
        EditTransactionDialog(
            transaction = transaction,
            customCategories = uiState.customCategories[transaction.direction].orEmpty(),
            onDismiss = { screenViewModel.editTransaction(null) },
            onSave = { description, category, customCategory, subcategory, status, amount, dueDate, plannedDate, paidAt, apply, scope ->
                screenViewModel.saveTransaction(
                    description, category, customCategory, subcategory, status, amount,
                    dueDate, plannedDate, paidAt, apply, scope,
                )
            },
        )
    }
}
