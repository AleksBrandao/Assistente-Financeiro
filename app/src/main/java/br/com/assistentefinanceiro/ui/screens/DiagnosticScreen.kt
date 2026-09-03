package br.com.assistentefinanceiro.ui.screens

import br.com.assistentefinanceiro.data.FinancialRepository
import android.content.Context
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
import br.com.assistentefinanceiro.ui.viewmodels.DiagnosticViewModel
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
internal fun DiagnosticScreen(
    repository: FinancialRepository,
    preferences: BankPackagePreferences,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val screenViewModel: DiagnosticViewModel = viewModel(
        factory = ScreenViewModelFactory {
            DiagnosticViewModel(context, repository, preferences)
        },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    val allowed = uiState.allowedPackages
    val candidates = uiState.candidates
    val events = uiState.events
    val enabled = uiState.notificationAccessEnabled

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnóstico financeiro") },
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
                val semantic = MaterialTheme.financeColors
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
                        modifier = Modifier.padding(FinanceSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FinanceIconTile(
                                icon = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(FinanceSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Captura de notificações",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Controle local e por aplicativo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FinanceStatusPill(
                                text = if (enabled) "Ativa" else "Desativada",
                                foreground = if (enabled) {
                                    semantic.onRealizedContainer
                                } else MaterialTheme.colorScheme.onErrorContainer,
                                background = if (enabled) {
                                    semantic.realizedContainer
                                } else MaterialTheme.colorScheme.errorContainer,
                                icon = if (enabled) {
                                    Icons.Rounded.CheckCircle
                                } else Icons.Rounded.Warning,
                            )
                        }
                        Text(
                            "O conteúdo só é armazenado depois que você autoriza um " +
                                "aplicativo financeiro específico.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                        ) {
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = null)
                                Spacer(Modifier.width(FinanceSpacing.xs))
                                Text("Configurar")
                            }
                            OutlinedButton(
                                onClick = screenViewModel::refresh,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null)
                                Spacer(Modifier.width(FinanceSpacing.xs))
                                Text("Atualizar")
                            }
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
                    FinanceEmptyState(
                        icon = Icons.Rounded.Apps,
                        title = "Nenhum aplicativo detectado",
                        description = "Após ativar o acesso, aguarde uma notificação do " +
                            "banco e toque em Atualizar.",
                    )
                }
            }
            items(candidates) { (packageName, label) ->
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
                            .padding(FinanceSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FinanceIconTile(
                            icon = Icons.Rounded.AccountBalance,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(FinanceSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = packageName in allowed,
                            onCheckedChange = {
                                screenViewModel.setPackageAllowed(packageName, it)
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
                        onClick = screenViewModel::clearEvents,
                    ) {
                        Text("Limpar")
                    }
                }
            }
            if (events.isEmpty()) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.NotificationsNone,
                        title = "Nenhum evento armazenado",
                        description = "As notificações autorizadas aparecerão aqui para " +
                            "você conferir a classificação.",
                    )
                }
            }
            items(events, key = { it.id }) { event ->
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
                        modifier = Modifier.padding(FinanceSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                    ) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            event.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val statusText = when (event.classification) {
                            NotificationClassification.TRANSACTION -> when (event.transactionType) {
                                FinancialTransactionType.CARD_PURCHASE ->
                                    "Reconhecida: final ${event.cardLastFour} · ${formatCurrency(event.amount)} · ${event.merchant}"
                                FinancialTransactionType.PIX_RECEIVED ->
                                    "Entrada reconhecida: PIX · ${formatCurrency(event.amount)}"
                                FinancialTransactionType.IMPORTED_EXPENSE,
                                FinancialTransactionType.IMPORTED_INCOME,
                                FinancialTransactionType.MANUAL_EXPENSE,
                                FinancialTransactionType.MANUAL_INCOME ->
                                    "Movimentação importada"
                                null -> "Transação reconhecida"
                            }
                            NotificationClassification.IGNORED_PROMOTION ->
                                "Ignorada: ${event.classificationReason ?: "promoção"}"
                            NotificationClassification.PENDING_RULE -> "Aguardando regra"
                        }
                        val semantic = MaterialTheme.financeColors
                        val statusForeground = when (event.classification) {
                            NotificationClassification.TRANSACTION ->
                                semantic.onIncomeContainer
                            NotificationClassification.IGNORED_PROMOTION ->
                                MaterialTheme.colorScheme.onSurfaceVariant
                            NotificationClassification.PENDING_RULE ->
                                semantic.onPendingContainer
                        }
                        val statusBackground = when (event.classification) {
                            NotificationClassification.TRANSACTION ->
                                semantic.incomeContainer
                            NotificationClassification.IGNORED_PROMOTION ->
                                MaterialTheme.colorScheme.surfaceVariant
                            NotificationClassification.PENDING_RULE ->
                                semantic.pendingContainer
                        }
                        val statusIcon = when (event.classification) {
                            NotificationClassification.TRANSACTION -> Icons.Rounded.CheckCircle
                            NotificationClassification.IGNORED_PROMOTION -> Icons.Rounded.Block
                            NotificationClassification.PENDING_RULE -> Icons.Rounded.Schedule
                        }
                        FinanceStatusPill(
                            text = statusText,
                            foreground = statusForeground,
                            background = statusBackground,
                            icon = statusIcon,
                        )
                    }
                }
            }
        }
    }
}
