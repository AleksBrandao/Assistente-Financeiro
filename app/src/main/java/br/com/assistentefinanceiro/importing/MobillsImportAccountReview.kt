package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialAccountIdentity
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.time.LocalDate
import java.util.Locale

data class MobillsImportAccountReview(
    val normalizedName: String,
    val displayName: String,
    val existingAccountId: Long?,
    val selectedType: FinancialAccountType?,
    val firstTransactionDate: LocalDate?,
) {
    val isExisting: Boolean
        get() = existingAccountId != null
}

object MobillsImportAccountPlanner {
    fun build(
        preview: MobillsImportPreview,
        existingAccounts: List<FinancialAccountRecord>,
    ): List<MobillsImportAccountReview> {
        val existingByNormalizedName = existingAccounts.associateBy { account ->
            FinancialAccountIdentity.normalize(account.name)
        }

        return preview.rows
            .asSequence()
            .filter { row ->
                row.disposition != ImportDisposition.REJECTED && row.account.isNotBlank()
            }
            .groupBy { row -> FinancialAccountIdentity.normalize(row.account) }
            .filterKeys(String::isNotBlank)
            .map { (normalizedName, rows) ->
                val existing = existingByNormalizedName[normalizedName]
                MobillsImportAccountReview(
                    normalizedName = normalizedName,
                    displayName = existing?.name ?: rows.first().account.trim(),
                    existingAccountId = existing?.id,
                    selectedType = existing?.type,
                    firstTransactionDate = rows.mapNotNull { row -> row.date }.minOrNull(),
                )
            }
            .sortedBy { review -> review.displayName.lowercase(Locale.ROOT) }
    }
}
