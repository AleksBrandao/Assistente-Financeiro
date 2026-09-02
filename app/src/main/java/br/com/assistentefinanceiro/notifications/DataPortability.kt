package br.com.assistentefinanceiro.notifications

import java.time.Instant

data class BackupPreview(
    val createdAt: Instant,
    val databaseVersion: Int,
    val tableCount: Int,
    val transactionCount: Int,
    val accountCount: Int,
)

data class DeletedTransactionGroup(
    val groupId: String,
    val description: String,
    val itemCount: Int,
    val deletedAt: Instant,
)

sealed interface BackupValidationResult {
    data class Valid(val preview: BackupPreview) : BackupValidationResult
    data class Invalid(val reason: String) : BackupValidationResult
}
