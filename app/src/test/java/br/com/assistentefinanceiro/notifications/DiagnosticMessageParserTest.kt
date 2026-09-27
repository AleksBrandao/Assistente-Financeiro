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
