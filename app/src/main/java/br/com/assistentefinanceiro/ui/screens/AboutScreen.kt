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
import br.com.assistentefinanceiro.ui.viewmodels.AboutViewModel
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
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val screenViewModel: AboutViewModel = viewModel(
        factory = ScreenViewModelFactory { AboutViewModel(context) },
    )
    val uiState by screenViewModel.uiState.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Sobre") },
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
                            .padding(FinanceSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
                    ) {
                        FinanceIconTile(
                            icon = Icons.Rounded.AccountBalanceWallet,
                            contentDescription = null,
                            size = 64,
                        )
                        Text(
                            "Assistente Financeiro",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        FinanceStatusPill(
                            text = "Versão ${uiState.versionName} · código ${uiState.versionCode}",
                            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                            background = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                }
            }
            item {
                Text(
                    "Seus dados financeiros permanecem armazenados localmente no aparelho. " +
                        "Use Dados e segurança para criar cópias de recuperação.",
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/AleksBrandao/Assistente-Financeiro/releases/latest"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.NewReleases, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Ver última versão no GitHub")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/AleksBrandao/Assistente-Financeiro"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Abrir projeto no GitHub")
                }
            }
        }
    }
}
