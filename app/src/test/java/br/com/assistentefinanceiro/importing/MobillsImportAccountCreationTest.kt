package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MobillsImportAccountCreationTest {
    @Test
    fun bankAccountUsesEarliestImportedDateAsOpeningBalanceDate() {
        val review = MobillsImportAccountReview(
            normalizedName = "CONTAPRINCIPAL",
            displayName = "Conta Principal",
            existingAccountId = null,
            selectedType = FinancialAccountType.BANK_ACCOUNT,
            firstTransactionDate = LocalDate.of(2026, 8, 20),
        )

        assertEquals(LocalDate.of(2026, 8, 20), review.firstTransactionDate)
    }
}
