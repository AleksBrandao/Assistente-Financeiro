package br.com.assistentefinanceiro.notifications

import java.text.Normalizer
import java.util.Locale
import java.math.BigDecimal
import java.time.LocalDate

enum class FinancialAccountType(val displayName: String) {
    BANK_ACCOUNT("Conta bancária"),
    CREDIT_CARD("Cartão de crédito");

    companion object {
        fun fromStored(value: String?): FinancialAccountType =
            entries.firstOrNull { it.name == value } ?: BANK_ACCOUNT
    }
}

data class FinancialAccountRecord(
    val id: Long,
    val name: String,
    val type: FinancialAccountType,
    val closingDay: Int? = null,
    val dueDay: Int? = null,
    val isDefault: Boolean = false,
    val cardIdentifiers: String? = null,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val openingBalanceDate: LocalDate? = null,
)

enum class AccountMovementDirection { CREDIT, DEBIT }

enum class AccountMovementType { CARD_PAYMENT, TRANSFER }

data class AccountMovementRecord(
    val id: Long,
    val direction: AccountMovementDirection,
    val type: AccountMovementType,
    val amount: BigDecimal,
    val occurredAt: LocalDate,
    val description: String,
    val relatedAccountName: String? = null,
)

data class AccountBalanceSummary(
    val realizedBalance: BigDecimal,
    val projectedBalance: BigDecimal,
    val pendingIncome: BigDecimal,
    val pendingExpense: BigDecimal,
)

data class AccountBalanceEntry(
    val direction: FinancialTransactionDirection,
    val amount: BigDecimal,
    val status: TransactionStatus,
)

object AccountBalanceCalculator {
    fun calculate(
        openingBalance: BigDecimal,
        transactions: List<AccountBalanceEntry>,
        movements: List<AccountMovementRecord>,
    ): AccountBalanceSummary {
        var realized = openingBalance
        var pendingIncome = BigDecimal.ZERO
        var pendingExpense = BigDecimal.ZERO
        transactions.forEach { transaction ->
            if (transaction.status == TransactionStatus.REALIZED) {
                realized += if (transaction.direction == FinancialTransactionDirection.INCOME) {
                    transaction.amount
                } else -transaction.amount
            } else if (transaction.direction == FinancialTransactionDirection.INCOME) {
                pendingIncome += transaction.amount
            } else {
                pendingExpense += transaction.amount
            }
        }
        movements.forEach { movement ->
            realized += if (movement.direction == AccountMovementDirection.CREDIT) {
                movement.amount
            } else -movement.amount
        }
        return AccountBalanceSummary(
            realizedBalance = realized,
            projectedBalance = realized + pendingIncome - pendingExpense,
            pendingIncome = pendingIncome,
            pendingExpense = pendingExpense,
        )
    }
}

fun FinancialAccountRecord.matchesCardLastFour(lastFour: String?): Boolean {
    val target = lastFour?.filter(Char::isDigit)?.takeLast(4) ?: return false
    return cardIdentifiers
        ?.split(",", "/", ";", " ")
        ?.map { it.filter(Char::isDigit).takeLast(4) }
        ?.any { it == target } == true
}

object FinancialAccountIdentity {
    fun normalize(name: String): String = Normalizer
        .normalize(name.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Za-z0-9]"), "")
        .uppercase(Locale.ROOT)

    fun normalizedIdentifiers(value: String?): String? = value
        ?.split(",", "/", ";", " ")
        ?.map { it.filter(Char::isDigit).takeLast(4) }
        ?.filter { it.length == 4 }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(",")
}
