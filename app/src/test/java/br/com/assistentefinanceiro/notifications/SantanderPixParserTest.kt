package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SantanderPixParserTest {
    @Test
    fun parsesReceivedPix() {
        val parsed = SantanderPixParser.parse(
            title = "Você acaba de receber um PIX!",
            body = "PIX recebido em 29/08/2026 as 12:38 no valor de R$ 58,00.",
        )

        assertEquals(BigDecimal("58.00"), parsed?.amount)
        assertEquals(LocalDateTime.of(2026, 8, 29, 12, 38), parsed?.occurredAt)
    }

    @Test
    fun parsesAmountWithThousandsSeparator() {
        val parsed = SantanderPixParser.parse(
            title = "Você acaba de receber um PIX!",
            body = "PIX recebido em 29/08/2026 às 12:38 no valor de R$ 1.234,56.",
        )

        assertEquals(BigDecimal("1234.56"), parsed?.amount)
    }

    @Test
    fun ignoresSentPix() {
        assertNull(
            SantanderPixParser.parse(
                title = "PIX enviado",
                body = "PIX enviado em 29/08/2026 às 12:38 no valor de R$ 58,00.",
            )
        )
    }

    @Test
    fun ignoresMalformedDate() {
        assertNull(
            SantanderPixParser.parse(
                title = "Você acaba de receber um PIX!",
                body = "PIX recebido em 99/99/2026 às 12:38 no valor de R$ 58,00.",
            )
        )
    }
}
