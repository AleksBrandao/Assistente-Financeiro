package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.data.ExternalBillImportDraft
import br.com.assistentefinanceiro.data.ExternalBillPaymentDraft
import br.com.assistentefinanceiro.data.ExternalDataProvider
import br.com.assistentefinanceiro.data.ExternalTransactionImportDraft
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class PluggyControlledImportPlan(
    val drafts: List<ExternalTransactionImportDraft>,
    val billDrafts: List<ExternalBillImportDraft>,
    val selectedAccounts: Int,
    val matchedExisting: Int,
    val skippedReview: Int,
    val skippedCreditCardPayments: Int,
    val skippedOutsideWindow: Int,
    val localTransactionCount: Int,
    val localPluggyTransactionCount: Int,
    val windowStartDate: LocalDate?,
    val windowEndDate: LocalDate,
) {
    val importable: Int get() = drafts.size
}

/**
 * Builds an explicit import plan. PENDING movements are retained as pending, already imported
 * Pluggy movements are sent through again so persistence can update them idempotently, and card
 * credits are retained unless they match a payment explicitly reported by a Pluggy Bill.
 */
object PluggyControlledImportPlanner {
    fun plan(
        datasets: List<PluggyAccountDataset>,
        reconciliation: PluggyReconciliationPreview,
        selectedExternalAccountIds: Set<String>,
        localTransactions: List<FinancialTransactionRecord>,
        today: LocalDate = LocalDate.now(),
        lookbackDays: Long? = 90,
        startDate: LocalDate? = null,
        endDate: LocalDate = today,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PluggyControlledImportPlan {
        require(lookbackDays == null || lookbackDays > 0)
        require(!endDate.isAfter(today)) { "endDate cannot be in the future" }
        val earliest = startDate ?: lookbackDays?.let(today::minusDays)
        require(earliest == null || !earliest.isAfter(endDate))

        var matchedExisting = 0
        var skippedReview = 0
        var skippedCreditCardPayments = 0
        var skippedOutsideWindow = 0
        val drafts = mutableListOf<ExternalTransactionImportDraft>()
        val billDrafts = mutableListOf<ExternalBillImportDraft>()

        val selectedResults = reconciliation.accounts.filter { result ->
            result.pluggyAccountExternalId in selectedExternalAccountIds &&
                result.localAccountId != null &&
                result.status in setOf(
                    PluggyReconciliationStatus.CONFIRMED,
                    PluggyReconciliationStatus.STRONG,
                )
        }
        val datasetsById = datasets.associateBy { it.account.externalId }

        fun insideWindow(date: LocalDate): Boolean =
            (earliest == null || !date.isBefore(earliest)) && !date.isAfter(endDate)

        selectedResults.forEach { accountResult ->
            val dataset = datasetsById[accountResult.pluggyAccountExternalId] ?: return@forEach
            val localAccountId = checkNotNull(accountResult.localAccountId)
            val localForAccount = localTransactions.filter { it.accountId == localAccountId }
            // PLUGGY rows must pass through persistence again so PENDING -> POSTED and other
            // source-side changes can be synchronized. Only non-Pluggy rows participate in the
            // duplicate/reconciliation heuristic here.
            val localForMatching = localForAccount.filter { it.origin != TransactionOrigin.PLUGGY }
            val billsById = dataset.bills.associateBy { it.externalId }
            val billPayments = dataset.bills.flatMap { it.payments }
            val referencedBillIds = mutableSetOf<String>()

            dataset.transactions.forEach { remote ->
                val accountingDate = remote.date.atZone(zoneId).toLocalDate()
                if (!insideWindow(accountingDate)) {
                    skippedOutsideWindow++
                    return@forEach
                }
                if (
                    dataset.account.type == PluggyAccountType.CREDIT &&
                    remote.direction == PluggyTransactionDirection.CREDIT &&
                    matchesBillPayment(remote, billPayments, zoneId)
                ) {
                    skippedCreditCardPayments++
                    return@forEach
                }
                when (
                    PluggyReconciliationEngine.transactionMatchStatus(
                        remote = remote,
                        local = localForMatching,
                        zoneId = zoneId,
                    )
                ) {
                    PluggyTransactionMatchStatus.MATCHED -> {
                        matchedExisting++
                        return@forEach
                    }
                    PluggyTransactionMatchStatus.REVIEW -> {
                        skippedReview++
                        return@forEach
                    }
                    PluggyTransactionMatchStatus.UNMATCHED -> Unit
                }

                val direction = when (remote.direction) {
                    PluggyTransactionDirection.CREDIT -> FinancialTransactionDirection.INCOME
                    PluggyTransactionDirection.DEBIT -> FinancialTransactionDirection.EXPENSE
                }
                val type = transactionType(dataset.account.type, remote, direction)
                val (category, customCategory) = importedCategory(remote.category, direction)
                val officialBill = remote.billExternalId?.let(billsById::get)
                remote.billExternalId?.let(referencedBillIds::add)
                drafts += ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = remote.externalId,
                    externalAccountId = dataset.account.externalId,
                    localAccountId = localAccountId,
                    direction = direction,
                    type = type,
                    amount = remote.absoluteAmount,
                    occurredAt = remote.date.atZone(zoneId).toLocalDateTime(),
                    description = remote.description.trim().ifBlank { defaultDescription(type) },
                    status = when (remote.status) {
                        PluggyTransactionStatus.POSTED -> TransactionStatus.REALIZED
                        PluggyTransactionStatus.PENDING -> TransactionStatus.PENDING
                    },
                    category = category,
                    customCategory = customCategory,
                    originalCategory = remote.category,
                    origin = TransactionOrigin.PLUGGY,
                    purchaseAt = remote.purchaseDate?.toString(),
                    sourceCategoryId = remote.categoryId,
                    operationType = remote.operationType,
                    paymentMethod = remote.paymentMethod,
                    installmentNumber = remote.installmentNumber,
                    totalInstallments = remote.totalInstallments,
                    billForecastPeriod = remote.billForecastDate,
                    externalBillId = remote.billExternalId,
                    invoiceClosingDate = officialBill?.closingDate,
                    invoiceDueDate = officialBill?.dueDate,
                )
            }

            dataset.bills
                // A transaction selected for import may already reference the next/open Bill even
                // when that Bill's due date is just outside the requested date window. Import the
                // referenced Bill too so externalBillId can always be resolved locally.
                .filter { insideWindow(it.dueDate) || it.externalId in referencedBillIds }
                .forEach { bill ->
                    billDrafts += ExternalBillImportDraft(
                        provider = ExternalDataProvider.PLUGGY,
                        externalBillId = bill.externalId,
                        externalAccountId = dataset.account.externalId,
                        localAccountId = localAccountId,
                        dueDate = bill.dueDate,
                        closingDate = bill.closingDate,
                        totalAmount = bill.totalAmount.abs(),
                        payments = bill.payments
                            .filter {
                                it.amount.signum() > 0 && !it.paymentDate.isAfter(today)
                            }
                            .map { payment ->
                                ExternalBillPaymentDraft(
                                    externalPaymentId = payment.externalId,
                                    amount = payment.amount.abs(),
                                    paidAt = payment.paymentDate,
                                )
                            },
                        financeChargeTotal = bill.financeCharges
                            .fold(java.math.BigDecimal.ZERO) { total, charge -> total + charge.amount.abs() },
                    )
                }
        }

        return PluggyControlledImportPlan(
            drafts = drafts,
            billDrafts = billDrafts,
            selectedAccounts = selectedResults.size,
            matchedExisting = matchedExisting,
            skippedReview = skippedReview,
            skippedCreditCardPayments = skippedCreditCardPayments,
            skippedOutsideWindow = skippedOutsideWindow,
            localTransactionCount = localTransactions.size,
            localPluggyTransactionCount = localTransactions.count { it.origin == TransactionOrigin.PLUGGY },
            windowStartDate = earliest,
            windowEndDate = endDate,
        )
    }

    private fun matchesBillPayment(
        remote: PluggyTransactionSnapshot,
        payments: List<PluggyBillPaymentSnapshot>,
        zoneId: ZoneId,
    ): Boolean {
        val remoteDate = remote.date.atZone(zoneId).toLocalDate()
        return payments.any { payment ->
            remote.absoluteAmount.compareTo(payment.amount.abs()) == 0 &&
                kotlin.math.abs(ChronoUnit.DAYS.between(remoteDate, payment.paymentDate)) <= 1
        }
    }

    private fun transactionType(
        accountType: PluggyAccountType,
        remote: PluggyTransactionSnapshot,
        direction: FinancialTransactionDirection,
    ): FinancialTransactionType = when {
        accountType == PluggyAccountType.CREDIT &&
            direction == FinancialTransactionDirection.EXPENSE -> FinancialTransactionType.CARD_PURCHASE
        accountType == PluggyAccountType.CREDIT -> FinancialTransactionType.IMPORTED_INCOME
        direction == FinancialTransactionDirection.INCOME &&
            (remote.operationType.equals("PIX", ignoreCase = true) ||
                remote.paymentMethod.equals("PIX", ignoreCase = true)) ->
            FinancialTransactionType.PIX_RECEIVED
        direction == FinancialTransactionDirection.INCOME ->
            FinancialTransactionType.IMPORTED_INCOME
        else -> FinancialTransactionType.IMPORTED_EXPENSE
    }

    /** Keep the category label supplied by Pluggy. The user may replace it later in the app. */
    private fun importedCategory(
        source: String?,
        direction: FinancialTransactionDirection,
    ): Pair<TransactionCategory, String?> {
        val sourceLabel = source?.trim()?.takeIf(String::isNotBlank)
            ?: return TransactionCategory.UNCATEGORIZED to null
        val fallback = if (direction == FinancialTransactionDirection.INCOME) {
            TransactionCategory.OTHER_INCOME
        } else {
            TransactionCategory.OTHER_EXPENSE
        }
        return fallback to sourceLabel
    }

    private fun defaultDescription(type: FinancialTransactionType): String = when (type) {
        FinancialTransactionType.CARD_PURCHASE -> "Compra no cartão"
        FinancialTransactionType.PIX_RECEIVED -> "PIX recebido"
        FinancialTransactionType.IMPORTED_EXPENSE -> "Despesa Pluggy"
        FinancialTransactionType.IMPORTED_INCOME -> "Crédito Pluggy"
        FinancialTransactionType.MANUAL_EXPENSE -> "Despesa"
        FinancialTransactionType.MANUAL_INCOME -> "Receita"
    }
}
