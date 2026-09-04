package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

class AdditionalBankNotificationParsersTest {
    private val receivedAt = LocalDateTime.of(2026, 9, 3, 18, 25)

    @Test
    fun registryRecognizesSupportedBankPackages() {
        assertTrue(BankNotificationParserRegistry.supports("com.nu.production", "Nubank"))
        assertTrue(BankNotificationParserRegistry.supports("br.com.bradesco.cartoes", "Bradesco Cartões"))
        assertTrue(BankNotificationParserRegistry.supports("com.santander.app", "Santander"))
        assertFalse(BankNotificationParserRegistry.supports("com.example.bank", "Banco sem parser"))
    }

    @Test
    fun parsesVerifiedNubankCreditPurchaseFormat() {
        val parsed = NubankNotificationParser.parse(
            BankNotification(
                packageName = "com.nu.production",
                appLabel = "Nubank",
                title = "Compra no crédito aprovada",
                body = "Compra de R$ 65,58 APROVADA em MAMBO VILA LEOPOLDINA para o cartão com final 6652.",
                receivedAt = receivedAt,
            )
        )

        assertNotNull(parsed)
        assertEquals(FinancialTransactionType.CARD_PURCHASE, parsed?.type)
        assertEquals(BigDecimal("65.58"), parsed?.amount)
        assertEquals("6652", parsed?.cardLastFour)
        assertEquals("MAMBO VILA LEOPOLDINA", parsed?.merchant)
        assertEquals(receivedAt, parsed?.occurredAt)
    }

    @Test
    fun listenerContextMakesClassifierUseAndroidPostedTime() {
        val postedAt = Instant.parse("2026-09-03T21:25:00Z").toEpochMilli()
        val expected = NotificationReceivedAtContext.fromPostedAt(postedAt)

        val result = NotificationReceivedAtContext.withPostedAt(postedAt) {
            FinancialNotificationClassifier.classify(
                packageName = "com.nu.production",
                appLabel = "Nubank",
                title = "Compra no crédito aprovada",
                body = "Compra de R$ 65,58 APROVADA em MAMBO VILA LEOPOLDINA para o cartão com final 6652.",
            )
        }

        assertEquals(NotificationClassification.TRANSACTION, result.classification)
        assertEquals(expected, result.transaction?.occurredAt)
    }

    @Test
    fun doesNotGuessNubankDebitFormat() {
        val parsed = NubankNotificationParser.parse(
            BankNotification(
                packageName = "com.nu.production",
                appLabel = "Nubank",
                title = "Compra no débito aprovada",
                body = "Compra de R$ 15,00 em NEB PARKING",
                receivedAt = receivedAt,
            )
        )

        assertNull(parsed)
    }

    @Test
    fun parsesDetailedBradescoCardsPurchaseAlert() {
        val parsed = BradescoCardsNotificationParser.parse(
            BankNotification(
                packageName = "br.com.bradesco.cartoes",
                appLabel = "Bradesco Cartões",
                title = "BRADESCO CARTOES:",
                body = "COMPRA APROVADA NO CARTAO FINAL 7731 EM 19/03/2026 06:25. VALOR DE R$ 31,90 EBN",
                receivedAt = receivedAt,
            )
        )

        assertNotNull(parsed)
        assertEquals(FinancialTransactionType.CARD_PURCHASE, parsed?.type)
        assertEquals(BigDecimal("31.90"), parsed?.amount)
        assertEquals("7731", parsed?.cardLastFour)
        assertEquals("EBN", parsed?.merchant)
        assertEquals(LocalDateTime.of(2026, 3, 19, 6, 25), parsed?.occurredAt)
    }

    @Test
    fun parsesBradescoInstallmentVariantWithoutTreatingInstallmentTextAsMerchant() {
        val parsed = BradescoCardsNotificationParser.parse(
            BankNotification(
                packageName = "br.com.bradesco.cartoes",
                appLabel = "Bradesco Cartões",
                title = "",
                body = "BRADESCO CARTOES: COMPRA APROVADA NO CARTAO FINAL 9402 EM 29/06/2026 14:40 NO VALOR DE R$ 1.999,99 EM 3 X PGZ*ANNEMOVEISE RIO",
                receivedAt = receivedAt,
            )
        )

        assertEquals(BigDecimal("1999.99"), parsed?.amount)
        assertEquals("PGZ*ANNEMOVEISE RIO", parsed?.merchant)
    }

    @Test
    fun sparseBradescoNotificationStaysPendingInsteadOfInventingTransactionData() {
        val result = FinancialNotificationClassifier.classify(
            packageName = "br.com.bradesco.cartoes",
            appLabel = "Bradesco Cartões",
            title = "Compra",
            body = "",
            receivedAt = receivedAt,
        )

        assertEquals(NotificationClassification.PENDING_RULE, result.classification)
        assertNull(result.transaction)
    }

    @Test
    fun classifierDispatchesNubankThroughCommonRegistry() {
        val result = FinancialNotificationClassifier.classify(
            packageName = "com.nu.production",
            appLabel = "Nubank",
            title = "Compra no crédito aprovada",
            body = "Compra de R$ 140,02 APROVADA em AutoPosto para o cartão com final 6652.",
            receivedAt = receivedAt,
        )

        assertEquals(NotificationClassification.TRANSACTION, result.classification)
        assertEquals("Compra aprovada reconhecida", result.reason)
        assertEquals(BigDecimal("140.02"), result.transaction?.amount)
        assertEquals(receivedAt, result.transaction?.occurredAt)
    }
}
