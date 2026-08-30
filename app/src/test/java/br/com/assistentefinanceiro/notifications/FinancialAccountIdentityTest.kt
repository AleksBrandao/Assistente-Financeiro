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
}
