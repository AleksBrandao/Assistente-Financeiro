package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialAccountIdentityTest {
    @Test
    fun normalizesNamesWithoutAccentsOrPunctuation() {
        assertEquals(
            "CARTAOPRINCIPALEUR",
            FinancialAccountIdentity.normalize(" Cartão principal / EUR "),
        )
        assertEquals("CONTADIGITAL", FinancialAccountIdentity.normalize("Conta digital"))
    }

    @Test
    fun normalizesAndMatchesCardIdentifiers() {
        assertEquals(
            "1234,5678",
            FinancialAccountIdentity.normalizedIdentifiers("1234 / 5678"),
        )
        val account = FinancialAccountRecord(
            id = 1,
            name = "Cartão de teste",
            type = FinancialAccountType.CREDIT_CARD,
            cardIdentifiers = "1234,5678",
        )
        assertEquals(true, account.matchesCardLastFour("5678"))
    }
}
