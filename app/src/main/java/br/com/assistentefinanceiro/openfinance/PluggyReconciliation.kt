package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountIdentity
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.TransactionCategory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class PluggyAccountDataset(
    val account: PluggyAccountSnapshot,
    val transactions: List<PluggyTransactionSnapshot>,
    val bills: List<PluggyBillSnapshot>,
)

enum class PluggyReconciliationStatus {
    CONFIRMED,
    STRONG,
    PROBABLE,
    REVIEW,
    UNMATCHED,
}

enum class PluggyTransactionMatchStatus {
    MATCHED,
    REVIEW,
    UNMATCHED,
}

data class PluggyReconciliationCounts(
    val matched: Int,
    val review: Int,
    val unmatched: Int,
)

data class PluggyAccountReconciliation(
    val pluggyAccountExternalId: String,
    val pluggyAccountName: String,
    val pluggyAccountType: PluggyAccountType,
    val status: PluggyReconciliationStatus,
    val localAccountId: Long?,
    val localAccountName: String?,
    val compatibleCandidateCount: Int,
    val reasons: List<String>,
    val transactionCounts: PluggyReconciliationCounts,
    val billCounts: PluggyReconciliationCounts,
    val pluggyCategoryCount: Int,
)

data class PluggyReconciliationPreview(
    val accounts: List<PluggyAccountReconciliation>,
    val distinctPluggyCategories: Int,
    val directCategoryMatches: Int,
) {
    val confirmedAccounts: Int
        get() = accounts.count { it.status == PluggyReconciliationStatus.CONFIRMED }
    val strongAccounts: Int get() = accounts.count { it.status == PluggyReconciliationStatus.STRONG }
    val probableAccounts: Int get() = accounts.count { it.status == PluggyReconciliationStatus.PROBABLE }
    val reviewAccounts: Int get() = accounts.count { it.status == PluggyReconciliationStatus.REVIEW }
    val unmatchedAccounts: Int get() = accounts.count { it.status == PluggyReconciliationStatus.UNMATCHED }
}

data class PluggyReconciliationInput(
    val pluggyAccounts: List<PluggyAccountDataset>,
    val localAccounts: List<FinancialAccountRecord>,
    val localTransactions: List<FinancialTransactionRecord>,
    val localInvoicesByAccount: Map<Long, List<CreditCardInvoiceRecord>>,
    val confirmedAccountLinks: Map<String, Long> = emptyMap(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

/** Conservative reconciliation used both for preview and controlled import. */
object PluggyReconciliationEngine {
    fun reconcile(input: PluggyReconciliationInput): PluggyReconciliationPreview {
        val accountResults = input.pluggyAccounts.map { dataset ->
            reconcileAccount(dataset, input)
        }
        val categories = input.pluggyAccounts
            .flatMap { it.transactions }
            .mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy(::normalize)
        val localCategoryKeys = TransactionCategory.entries
            .filterNot { it == TransactionCategory.UNCATEGORIZED }
            .flatMap { listOf(it.name, it.displayName) }
            .map(::normalize)
            .toSet()
        return PluggyReconciliationPreview(
            accounts = accountResults,
            distinctPluggyCategories = categories.size,
            directCategoryMatches = categories.count { normalize(it) in localCategoryKeys },
        )
    }

    fun transactionMatchStatus(
        remote: PluggyTransactionSnapshot,
        local: List<FinancialTransactionRecord>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PluggyTransactionMatchStatus {
        val candidates = local.filter { candidate ->
            directionMatches(remote, candidate) && amountMatches(remote, candidate)
        }
        if (candidates.isEmpty()) return PluggyTransactionMatchStatus.UNMATCHED
        val dated = candidates.filter { dateMatches(remote, it, zoneId) }
        return when {
            dated.size == 1 -> PluggyTransactionMatchStatus.MATCHED
            dated.size > 1 -> {
                val descriptions = dated.filter {
                    descriptionMatches(remote.description, it.description)
                }
                if (descriptions.size == 1) {
                    PluggyTransactionMatchStatus.MATCHED
                } else {
                    PluggyTransactionMatchStatus.REVIEW
                }
            }
            else -> {
                val near = candidates.filter { dateNear(remote, it, zoneId, 3) }
                if (near.size == 1) PluggyTransactionMatchStatus.REVIEW
                else PluggyTransactionMatchStatus.UNMATCHED
            }
        }
    }

    private fun reconcileAccount(
        dataset: PluggyAccountDataset,
        input: PluggyReconciliationInput,
    ): PluggyAccountReconciliation {
        val compatible = input.localAccounts.filter { isCompatible(dataset.account.type, it.type) }
        val confirmed = input.confirmedAccountLinks[dataset.account.externalId]?.let { localId ->
            compatible.firstOrNull { it.id == localId }
        }
        val scored = compatible.map { local ->
            scoreAccount(dataset, local, compatible.size)
        }.sortedByDescending { it.score }
        val top = scored.firstOrNull()
        val second = scored.getOrNull(1)
        val gap = if (top == null) 0 else top.score - (second?.score ?: 0)

        val status: PluggyReconciliationStatus
        val selected: FinancialAccountRecord?
        val reasons: List<String>
        if (confirmed != null) {
            status = PluggyReconciliationStatus.CONFIRMED
            selected = confirmed
            reasons = listOf("vínculo confirmado anteriormente")
        } else {
            status = when {
                top == null -> PluggyReconciliationStatus.UNMATCHED
                top.score >= 100 && gap >= 40 -> PluggyReconciliationStatus.STRONG
                top.score >= 50 && gap >= 20 -> PluggyReconciliationStatus.PROBABLE
                compatible.isNotEmpty() -> PluggyReconciliationStatus.REVIEW
                else -> PluggyReconciliationStatus.UNMATCHED
            }
            selected = top?.account?.takeIf {
                status == PluggyReconciliationStatus.STRONG ||
                    status == PluggyReconciliationStatus.PROBABLE
            }
            reasons = when {
                top == null -> listOf("Nenhuma conta local do mesmo tipo")
                selected != null -> top.reasons
                else -> listOf(
                    "Há ${compatible.size} conta(s) local(is) compatível(is), mas sem evidência suficiente para vincular",
                ) + top.reasons
            }
        }

        val localTransactions = selected?.let { account ->
            input.localTransactions.filter { it.accountId == account.id }
        }.orEmpty()
        val localInvoices = selected?.let { input.localInvoicesByAccount[it.id].orEmpty() }.orEmpty()

        return PluggyAccountReconciliation(
            pluggyAccountExternalId = dataset.account.externalId,
            pluggyAccountName = dataset.account.name,
            pluggyAccountType = dataset.account.type,
            status = status,
            localAccountId = selected?.id,
            localAccountName = selected?.name,
            compatibleCandidateCount = compatible.size,
            reasons = reasons,
            transactionCounts = if (selected == null) {
                PluggyReconciliationCounts(0, 0, dataset.transactions.size)
            } else {
                reconcileTransactions(dataset.transactions, localTransactions, input.zoneId)
            },
            billCounts = if (selected == null) {
                PluggyReconciliationCounts(0, 0, dataset.bills.size)
            } else {
                reconcileBills(dataset.bills, localInvoices)
            },
            pluggyCategoryCount = dataset.transactions
                .mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }
                .distinctBy(::normalize)
                .size,
        )
    }

    private data class AccountScore(
        val account: FinancialAccountRecord,
        val score: Int,
        val reasons: List<String>,
    )

    private fun scoreAccount(
        dataset: PluggyAccountDataset,
        local: FinancialAccountRecord,
        compatibleCount: Int,
    ): AccountScore {
        var score = 0
        val reasons = mutableListOf<String>()
        val pluggyName = normalize(dataset.account.name)
        val localName = normalize(local.name)
        if (pluggyName.isNotEmpty() && pluggyName == localName) {
            score += if (dataset.account.type == PluggyAccountType.BANK) 35 else 25
            reasons += "nome coincide"
        } else if (
            pluggyName.length >= 4 && localName.length >= 4 &&
            (pluggyName.contains(localName) || localName.contains(pluggyName))
        ) {
            score += 10
            reasons += "nome semelhante"
        }

        if (dataset.account.type == PluggyAccountType.CREDIT) {
            val pluggyLastFour = dataset.transactions.mapNotNull { it.cardLastFour }.toSet()
            val localLastFour = FinancialAccountIdentity.normalizedIdentifiers(local.cardIdentifiers)
                ?.split(',')
                ?.toSet()
                .orEmpty()
            if (pluggyLastFour.isNotEmpty() && pluggyLastFour.any { it in localLastFour }) {
                score += 100
                reasons += "final do cartão coincide"
            }
            dataset.bills.maxByOrNull { it.dueDate }?.let { bill ->
                if (local.dueDay != null && local.dueDay == bill.dueDate.dayOfMonth) {
                    score += 25
                    reasons += "dia de vencimento coincide"
                }
                if (
                    local.closingDay != null && bill.closingDate != null &&
                    local.closingDay == bill.closingDate.dayOfMonth
                ) {
                    score += 25
                    reasons += "dia de fechamento coincide"
                }
            }
        } else if (compatibleCount == 1) {
            score += 20
            reasons += "única conta bancária local compatível"
        }

        return AccountScore(local, score, reasons)
    }

    private fun reconcileTransactions(
        pluggy: List<PluggyTransactionSnapshot>,
        local: List<FinancialTransactionRecord>,
        zoneId: ZoneId,
    ): PluggyReconciliationCounts {
        val statuses = pluggy.map { transactionMatchStatus(it, local, zoneId) }
        return PluggyReconciliationCounts(
            matched = statuses.count { it == PluggyTransactionMatchStatus.MATCHED },
            review = statuses.count { it == PluggyTransactionMatchStatus.REVIEW },
            unmatched = statuses.count { it == PluggyTransactionMatchStatus.UNMATCHED },
        )
    }

    private fun reconcileBills(
        pluggy: List<PluggyBillSnapshot>,
        local: List<CreditCardInvoiceRecord>,
    ): PluggyReconciliationCounts {
        var matched = 0
        var review = 0
        var unmatched = 0
        pluggy.forEach { remote ->
            val sameDueDate = local.filter { it.dueDate == remote.dueDate }
            when {
                sameDueDate.any { moneyEquals(it.total, remote.totalAmount) } -> matched++
                sameDueDate.isNotEmpty() -> review++
                local.any {
                    moneyEquals(it.total, remote.totalAmount) &&
                        it.dueDate != null &&
                        kotlin.math.abs(ChronoUnit.DAYS.between(it.dueDate, remote.dueDate)) <= 1
                } -> review++
                else -> unmatched++
            }
        }
        return PluggyReconciliationCounts(matched, review, unmatched)
    }

    private fun isCompatible(pluggy: PluggyAccountType, local: FinancialAccountType): Boolean =
        when (pluggy) {
            PluggyAccountType.BANK -> local == FinancialAccountType.BANK_ACCOUNT
            PluggyAccountType.CREDIT -> local == FinancialAccountType.CREDIT_CARD
        }

    private fun directionMatches(
        remote: PluggyTransactionSnapshot,
        local: FinancialTransactionRecord,
    ): Boolean = when (remote.direction) {
        PluggyTransactionDirection.CREDIT -> local.direction == FinancialTransactionDirection.INCOME
        PluggyTransactionDirection.DEBIT -> local.direction == FinancialTransactionDirection.EXPENSE
    }

    private fun amountMatches(
        remote: PluggyTransactionSnapshot,
        local: FinancialTransactionRecord,
    ): Boolean {
        val localAmount = local.amount.toBigDecimalOrNull()?.abs() ?: return false
        return moneyEquals(remote.absoluteAmount, localAmount)
    }

    private fun moneyEquals(left: BigDecimal, right: BigDecimal): Boolean =
        left.setScale(2, java.math.RoundingMode.HALF_UP)
            .compareTo(right.setScale(2, java.math.RoundingMode.HALF_UP)) == 0

    private fun dateMatches(
        remote: PluggyTransactionSnapshot,
        local: FinancialTransactionRecord,
        zoneId: ZoneId,
    ): Boolean {
        val remoteDates = remoteDates(remote, zoneId)
        val localDates = localDates(local)
        if (remoteDates.any { r -> localDates.any { l -> dayDistance(r, l) <= 1 } }) return true
        val forecast = remote.billForecastDate ?: return false
        return localDates.any { YearMonth.from(it) == forecast }
    }

    private fun dateNear(
        remote: PluggyTransactionSnapshot,
        local: FinancialTransactionRecord,
        zoneId: ZoneId,
        maxDays: Long,
    ): Boolean {
        val remoteDates = remoteDates(remote, zoneId)
        val localDates = localDates(local)
        return remoteDates.any { r -> localDates.any { l -> dayDistance(r, l) <= maxDays } }
    }

    private fun remoteDates(remote: PluggyTransactionSnapshot, zoneId: ZoneId): Set<LocalDate> =
        buildSet {
            add(remote.date.atZone(zoneId).toLocalDate())
            add(remote.effectivePurchaseInstant.atZone(zoneId).toLocalDate())
        }

    private fun localDates(local: FinancialTransactionRecord): Set<LocalDate> = buildSet {
        parseDate(local.occurredAt)?.let(::add)
        parseDate(local.dueDate)?.let(::add)
        parseDate(local.plannedPaymentDate)?.let(::add)
        parseDate(local.paidAt)?.let(::add)
    }

    private fun parseDate(value: String?): LocalDate? = value
        ?.takeIf { it.length >= 10 }
        ?.take(10)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun dayDistance(left: LocalDate, right: LocalDate): Long =
        kotlin.math.abs(ChronoUnit.DAYS.between(left, right))

    private fun descriptionMatches(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.length < 4 || b.length < 4) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun normalize(value: String): String = FinancialAccountIdentity.normalize(value)
}
