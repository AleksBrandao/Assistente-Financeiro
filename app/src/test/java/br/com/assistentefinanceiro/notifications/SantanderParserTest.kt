package br.com.assistentefinanceiro.notifications

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class SantanderParserTest {
    @Test fun parsesPhysicalCardPurchase() {
        val parsed = SantanderParser.parse(
            "Compra aprovada!",
            "Compra no cartão final 1234, de R$ 10,00, em 26/08/26, às 13:45, em CANTINA IFCH, aprovada.",
        )
        assertNotNull(parsed)
        assertEquals("1234", parsed?.cardLastFour)
        assertEquals(BigDecimal("10.00"), parsed?.amount)
        assertEquals("CANTINA IFCH", parsed?.merchant)
    }

    @Test fun parsesVirtualCardPurchase() {
        val parsed = SantanderParser.parse(
            "Compra aprovada!",
            "Compra no cartão final 5678, de R$ 166,80, em 26/08/26, às 09:46, em SHOPEE .qualyce, aprovada.",
        )
        assertEquals("5678", parsed?.cardLastFour)
        assertEquals(BigDecimal("166.80"), parsed?.amount)
    }

    @Test fun ignoresUnrelatedNotification() {
        assertNull(SantanderParser.parse("Pix recebido", "Você recebeu um Pix."))
    }
}
