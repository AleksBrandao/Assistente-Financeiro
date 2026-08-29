package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialNotificationClassifierTest {
    @Test
    fun classifiesRecognizedPurchase() {
        val result = classify(
            title = "Compra aprovada!",
            body = "Compra no cartão final 3409, de R$ 10,00, em 26/08/26, às 13:45, em CANTINA IFCH, aprovada.",
        )

        assertEquals(NotificationClassification.TRANSACTION, result.classification)
        assertEquals(FinancialTransactionType.CARD_PURCHASE, result.transaction?.type)
        assertEquals(FinancialTransactionDirection.EXPENSE, result.transaction?.type?.direction)
        assertNotNull(result.transaction)
    }

    @Test
    fun classifiesReceivedPix() {
        val result = classify(
            title = "Você acaba de receber um PIX!",
            body = "PIX recebido em 29/08/2026 as 12:38 no valor de R$ 58,00.",
        )

        assertEquals(NotificationClassification.TRANSACTION, result.classification)
        assertEquals(FinancialTransactionType.PIX_RECEIVED, result.transaction?.type)
        assertEquals(FinancialTransactionDirection.INCOME, result.transaction?.type?.direction)
        assertEquals("58.00", result.transaction?.amount?.toPlainString())
    }

    @Test
    fun ignoresKnownCreditOffer() {
        val result = classify(
            title = "Você tem crédito disponível!",
            body = "O próximo passo da sua jornada pode precisar de um impulso e ele já está disponível. Confira as ofertas para o seu perfil.",
        )

        assertEquals(NotificationClassification.IGNORED_PROMOTION, result.classification)
        assertEquals("Oferta de crédito", result.reason)
        assertNull(result.transaction)
    }

    @Test
    fun keepsUnknownSantanderNotificationPending() {
        val result = classify(
            title = "Pix recebido",
            body = "Você recebeu um Pix.",
        )

        assertEquals(NotificationClassification.PENDING_RULE, result.classification)
        assertNull(result.transaction)
    }

    @Test
    fun doesNotApplySantanderRulesToAnotherApp() {
        val result = FinancialNotificationClassifier.classify(
            packageName = "com.example.otherbank",
            appLabel = "Outro banco",
            title = "Você tem crédito disponível!",
            body = "Confira as ofertas para o seu perfil.",
        )

        assertEquals(NotificationClassification.PENDING_RULE, result.classification)
        assertEquals("Aplicativo ainda sem classificador", result.reason)
    }

    private fun classify(title: String, body: String): NotificationClassificationResult =
        FinancialNotificationClassifier.classify(
            packageName = "com.santander.app",
            appLabel = "Santander",
            title = title,
            body = body,
        )
}
