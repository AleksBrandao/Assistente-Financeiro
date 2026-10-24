package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DiagnosticMessageParserTest {
    @Test
    fun parsesUsdCardPurchase() {
        val hint = DiagnosticMessageParser.parse(
            event(
                title = "Compra aprovada!",
                body = "Compra no cartão final 5253, de USD 20,00, em 23/09/26, às 22:42, em OPENAI .CHATGPT, aprovada.",
            ),
        )

        assertNotNull(hint)
        assertEquals("USD", hint?.currency)
        assertEquals(BigDecimal("20.00"), hint?.amount)
        assertEquals("5253", hint?.cardLastFour)
        assertEquals("OPENAI .CHATGPT", hint?.description)
    }

    @Test
    fun parsesEurPurchaseFromScreenshot() {
        val hint = DiagnosticMessageParser.parse(
            event(
                title = "Compra aprovada!",
                body = "Compra no cartão final 3409, de EUR 21,05, em 17/10/26, às 08:16, em Avolta MAD WT T, aprovada.",
            ),
        )

        assertNotNull(hint)
        assertEquals("EUR", hint?.currency)
        assertEquals(BigDecimal("21.05"), hint?.amount)
        assertEquals("3409", hint?.cardLastFour)
        assertEquals("Avolta MAD WT T", hint?.description)
        assertEquals(LocalDate.of(2026, 10, 17), hint?.occurredAt?.toLocalDate())
    }

    @Test
    fun parsesEurPurchaseWithPendingMerchantName() {
        val hint = DiagnosticMessageParser.parse(
            event(
                title = "Compra aprovada!",
                body = "Compra no cartão final 5253, de EUR 14,98, em 17/10/26, às 06:42, em UBER . PENDING, aprovada.",
            ),
        )

        assertNotNull(hint)
        assertEquals("EUR", hint?.currency)
        assertEquals(BigDecimal("14.98"), hint?.amount)
        assertEquals("5253", hint?.cardLastFour)
        assertEquals("UBER . PENDING", hint?.description)
    }

    @Test
    fun parsesBrlPurchaseWithoutConversion() {
        val hint = DiagnosticMessageParser.parse(
            event(
                title = "Compra aprovada!",
                body = "Compra no cartão final 3409, de R$ 10,00, em 17/10/26, às 08:16, em LOJA LOCAL, aprovada.",
            ),
        )

        assertEquals("BRL", hint?.currency)
        assertEquals(BigDecimal("10.00"), hint?.amount)
    }

    @Test
    fun parsesScheduledAutomaticDebitAsPendingExpense() {
        val hint = DiagnosticMessageParser.parse(
            event(
                title = "Agendamento de Débito Automático",
                body = "O(A) IRPF-SECRETARIA REC. FEDERAL agendou um débito automático em sua conta para 30/09, no valor de R$ 88,37. Clique aqui e confira.",
            ),
            today = LocalDate.of(2026, 9, 27),
        )

        assertNotNull(hint)
        assertEquals(FinancialTransactionDirection.EXPENSE, hint?.direction)
        assertEquals(BigDecimal("88.37"), hint?.amount)
        assertEquals(LocalDate.of(2026, 9, 30), hint?.occurredAt?.toLocalDate())
        assertEquals(true, hint?.pending)
    }

    private fun event(title: String, body: String) = DiagnosticEvent(
        id = 1,
        packageName = "com.santander.app",
        appLabel = "Santander",
        title = title,
        body = body,
        postedAt = 0,
        classification = NotificationClassification.PENDING_RULE,
        classificationReason = null,
        transactionType = null,
        occurredAt = null,
        cardLastFour = null,
        amount = null,
        merchant = null,
    )
}
