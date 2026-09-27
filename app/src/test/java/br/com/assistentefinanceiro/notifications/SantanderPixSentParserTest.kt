package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SantanderPixSentParserTest {
    @Test
    fun parsesSentPix() {
        val parsed = SantanderPixSentParser.parse(
            title = "Seu PIX foi enviado!",
            body = "PIX enviado em 26/09/2026 as 13:05 no valor de R$ 5,00.",
        )

        assertEquals(BigDecimal("5.00"), parsed?.amount)
        assertEquals(LocalDateTime.of(2026, 9, 26, 13, 5), parsed?.occurredAt)
    }

    @Test
    fun ignoresReceivedPix() {
        assertNull(
            SantanderPixSentParser.parse(
                title = "PIX recebido",
                body = "PIX recebido em 26/09/2026 as 13:05 no valor de R$ 5,00.",
            )
        )
    }
}
