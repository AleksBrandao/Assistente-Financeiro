package br.com.assistentefinanceiro.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.BackupPreview
import br.com.assistentefinanceiro.notifications.BackupValidationResult
import br.com.assistentefinanceiro.notifications.DeletedTransactionGroup
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DataManagementUiState(
    val message: String? = null,
    val pendingBackup: String? = null,
    val pendingPreview: BackupPreview? = null,
    val permanentDelete: DeletedTransactionGroup? = null,
    val deletedGroups: List<DeletedTransactionGroup> = emptyList(),
)

internal class DataManagementViewModel(
    context: Context,
    private val repository: FinancialRepository,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val _uiState = MutableStateFlow(
        DataManagementUiState(deletedGroups = repository.deletedTransactionGroups()),
    )
    val uiState: StateFlow<DataManagementUiState> = _uiState.asStateFlow()

    fun createBackup(openOutputStream: () -> OutputStream?) = runFileOperation {
        openOutputStream()?.bufferedWriter()?.use { writer ->
            writer.write(repository.createBackupJson())
        } ?: error("arquivo indisponível")
        "Backup criado com sucesso."
    }

    fun exportCsv(openOutputStream: () -> OutputStream?) = runFileOperation {
        openOutputStream()?.bufferedWriter()?.use { writer ->
            writer.write(repository.exportTransactionsCsv())
        } ?: error("arquivo indisponível")
        "Planilha CSV exportada com sucesso."
    }

    fun readBackup(openInputStream: () -> InputStream?) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    openInputStream()?.bufferedReader()?.use { it.readText() }
                        ?: error("arquivo indisponível")
                }
            }
            result.onSuccess { content ->
                when (val validation = repository.previewBackup(content)) {
                    is BackupValidationResult.Valid -> {
                        _uiState.value = _uiState.value.copy(
                            pendingBackup = content,
                            pendingPreview = validation.preview,
                        )
                    }
                    is BackupValidationResult.Invalid -> {
                        _uiState.value = _uiState.value.copy(message = validation.reason)
                    }
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    message = "Não foi possível ler o backup: ${error.message}",
                )
            }
        }
    }

    fun prepareShareCsv(startActivity: (Intent) -> Unit) = runFileOperation {
        val file = File(applicationContext.cacheDir, "AssistenteFinanceiro-movimentacoes.csv")
        file.writeText(repository.exportTransactionsCsv())
        val uri = FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.files",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            startActivity(Intent.createChooser(intent, "Compartilhar movimentações"))
        }
        "Arquivo preparado para compartilhamento."
    }

    fun restoreDeletedGroup(groupId: String) {
        val message = if (repository.restoreDeletedTransactionGroup(groupId)) {
            "Movimentação restaurada."
        } else "Não foi possível restaurar a movimentação."
        reload(message)
    }

    fun requestPermanentDelete(group: DeletedTransactionGroup?) {
        _uiState.value = _uiState.value.copy(permanentDelete = group)
    }

    fun dismissRestore() {
        _uiState.value = _uiState.value.copy(pendingPreview = null, pendingBackup = null)
    }

    fun confirmRestore() {
        val content = _uiState.value.pendingBackup
        dismissRestore()
        if (content != null) {
            val message = if (repository.restoreBackup(content)) {
                "Backup restaurado com sucesso."
            } else "A restauração falhou e os dados atuais foram preservados."
            reload(message)
        }
    }

    fun confirmPermanentDelete() {
        val group = _uiState.value.permanentDelete ?: return
        val deleted = repository.permanentlyDeleteTransactionGroup(group.groupId)
        reload(if (deleted) "Item removido definitivamente." else _uiState.value.message)
    }

    private fun runFileOperation(operation: suspend () -> String) {
        viewModelScope.launch {
            val message = runCatching { withContext(Dispatchers.IO) { operation() } }
                .getOrElse { "Não foi possível concluir: ${it.message ?: "erro desconhecido"}" }
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    private fun reload(message: String? = _uiState.value.message) {
        _uiState.value = DataManagementUiState(
            message = message,
            deletedGroups = repository.deletedTransactionGroups(),
        )
    }
}
