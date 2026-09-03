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
internal fun DataManagementScreen(
    store: DiagnosticStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var pendingPreview by remember { mutableStateOf<BackupPreview?>(null) }
    var permanentDelete by remember { mutableStateOf<DeletedTransactionGroup?>(null) }
    val deletedGroups = remember(refresh) { store.deletedTransactionGroups() }

    fun runFileOperation(operation: suspend () -> String) {
        scope.launch {
            message = runCatching { withContext(Dispatchers.IO) { operation() } }
                .getOrElse { "Não foi possível concluir: ${it.message ?: "erro desconhecido"}" }
        }
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) runFileOperation {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(store.createBackupJson())
            } ?: error("arquivo indisponível")
            "Backup criado com sucesso."
        }
    }
    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) runFileOperation {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(store.exportTransactionsCsv())
            } ?: error("arquivo indisponível")
            "Planilha CSV exportada com sucesso."
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("arquivo indisponível")
                }
            }
            result.onSuccess { content ->
                when (val validation = store.previewBackup(content)) {
                    is BackupValidationResult.Valid -> {
                        pendingBackup = content
                        pendingPreview = validation.preview
                    }
                    is BackupValidationResult.Invalid -> message = validation.reason
                }
            }.onFailure { message = "Não foi possível ler o backup: ${it.message}" }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Dados e segurança") },
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
                Text(
                    "Backup e recuperação",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Guarde uma cópia completa antes de trocar de aparelho ou fazer alterações importantes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = { createBackup.launch("AssistenteFinanceiro-backup-${LocalDate.now()}.json") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Backup, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Criar backup completo")
                }
            }
            item {
                OutlinedButton(
                    onClick = { openBackup.launch(arrayOf("application/json", "text/plain")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Restaurar um backup")
                }
            }
            item {
                HorizontalDivider()
                Text("Exportação", style = MaterialTheme.typography.headlineSmall)
                Text("O CSV pode ser aberto no Excel e não altera os dados do aplicativo.")
            }
            item {
                OutlinedButton(
                    onClick = { exportCsv.launch("AssistenteFinanceiro-movimentacoes-${LocalDate.now()}.csv") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Salvar movimentações em CSV")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        runFileOperation {
                            val file = File(context.cacheDir, "AssistenteFinanceiro-movimentacoes.csv")
                            file.writeText(store.exportTransactionsCsv())
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            withContext(Dispatchers.Main) {
                                context.startActivity(Intent.createChooser(intent, "Compartilhar movimentações"))
                            }
                            "Arquivo preparado para compartilhamento."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(FinanceSpacing.xs))
                    Text("Compartilhar CSV")
                }
            }
            message?.let { text ->
                item {
                    val failed = text.startsWith("Não ") ||
                        text.contains("falh", ignoreCase = true)
                    FinanceNoticeCard(
                        icon = if (failed) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                        title = if (failed) "Não foi possível concluir" else "Tudo certo",
                        description = text,
                        foreground = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else MaterialTheme.financeColors.onRealizedContainer,
                        background = if (failed) {
                            MaterialTheme.colorScheme.errorContainer
                        } else MaterialTheme.financeColors.realizedContainer,
                    )
                }
            }
            item {
                HorizontalDivider()
                Text("Lixeira", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (deletedGroups.isEmpty()) "Nenhuma movimentação excluída."
                    else "Movimentações excluídas manualmente podem ser restauradas.",
                )
            }
            if (deletedGroups.isEmpty()) {
                item {
                    FinanceEmptyState(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "Lixeira vazia",
                        description = "As movimentações excluídas poderão ser recuperadas aqui.",
                    )
                }
            }
            items(deletedGroups, key = { it.groupId }) { group ->
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
                        Text(group.description, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${group.itemCount} registro(s) · excluído em " +
                                group.deletedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                                    .format(SHORT_DATE_FORMATTER),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xs)) {
                            TextButton(onClick = {
                                if (store.restoreDeletedTransactionGroup(group.groupId)) {
                                    message = "Movimentação restaurada."
                                    refresh++
                                } else message = "Não foi possível restaurar a movimentação."
                            }) { Text("Restaurar") }
                            TextButton(onClick = { permanentDelete = group }) {
                                Text("Excluir definitivamente", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(FinanceSpacing.lg)) }
        }
    }

    pendingPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { pendingPreview = null; pendingBackup = null },
            title = { Text("Confirmar restauração") },
            text = {
                Text(
                    "Backup de ${preview.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().format(SHORT_DATE_FORMATTER)}\n" +
                        "${preview.transactionCount} movimentações · ${preview.accountCount} contas/cartões\n\n" +
                        "Os dados atuais serão substituídos. Crie um backup deles antes de continuar.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val content = pendingBackup
                    pendingPreview = null
                    pendingBackup = null
                    if (content != null) {
                        message = if (store.restoreBackup(content)) {
                            refresh++
                            "Backup restaurado com sucesso."
                        } else "A restauração falhou e os dados atuais foram preservados."
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreview = null; pendingBackup = null }) { Text("Cancelar") }
            },
        )
    }

    permanentDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { permanentDelete = null },
            title = { Text("Excluir definitivamente?") },
            text = { Text("${group.description} não poderá mais ser restaurada pela lixeira.") },
            confirmButton = {
                TextButton(onClick = {
                    if (store.permanentlyDeleteTransactionGroup(group.groupId)) {
                        message = "Item removido definitivamente."
                        refresh++
                    }
                    permanentDelete = null
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { permanentDelete = null }) { Text("Cancelar") } },
        )
    }
}
