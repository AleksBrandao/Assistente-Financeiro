package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialAccountIdentityTest {
    @Test
    fun normalizesNamesWithoutAccentsOrPunctuation() {
        assertEquals("CINZA64265253", FinancialAccountIdentity.normalize(" Cinza 6426 / 5253 "))
        assertEquals("SEMPARAR", FinancialAccountIdentity.normalize("Sem parar"))
    }

    @Test
    fun recognizesKnownCardNamesFromMobills() {
        listOf("CINZA", "VERMELHO", "PRETO", "CARREFOUR").forEach { name ->
            assertEquals(
                FinancialAccountType.CREDIT_CARD,
                FinancialAccountIdentity.inferredType(name),
            )
        }
        assertEquals(
            FinancialAccountType.BANK_ACCOUNT,
            FinancialAccountIdentity.inferredType("Santander"),
        )
    }

    @Test
    fun normalizesAndMatchesCardIdentifiers() {
        assertEquals(
            "6426,5253",
            FinancialAccountIdentity.normalizedIdentifiers("6426 / 5253"),
        )
        val account = FinancialAccountRecord(
            id = 1,
            name = "CINZA",
            type = FinancialAccountType.CREDIT_CARD,
            cardIdentifiers = "6426,5253",
        )
        assertEquals(true, account.matchesCardLastFour("5253"))
    }
}
