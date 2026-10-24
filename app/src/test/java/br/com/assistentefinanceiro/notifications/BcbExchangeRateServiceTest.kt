package br.com.assistentefinanceiro.notifications

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcbExchangeRateServiceTest {
    @Test
    fun usesEuroPtaxSellingRateEndpointForPurchaseDate() {
        val url = BcbExchangeRateService.quoteUrl("EUR", LocalDate.of(2026, 10, 17))
        assertTrue(url.contains("CotacaoMoedaPeriodo(moeda=@moeda"))
        assertTrue(url.contains("@moeda='EUR'"))
        assertTrue(url.contains("@dataInicial='10-10-2026'"))
        assertTrue(url.contains("@dataFinalCotacao='10-17-2026'"))
        assertTrue(url.contains("%24orderby=dataHoraCotacao%20desc&%24top=1"))
    }

    @Test
    fun keepsExistingDollarEndpoint() {
        val url = BcbExchangeRateService.quoteUrl("USD", LocalDate.of(2026, 10, 17))
        assertTrue(url.contains("CotacaoDolarPeriodo("))
        assertFalse(url.contains("@moeda='EUR'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedCurrency() {
        BcbExchangeRateService.quoteUrl("GBP", LocalDate.of(2026, 10, 17))
    }
}
