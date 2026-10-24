package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONObject

data class ExchangeRateQuote(
    val rate: BigDecimal,
    val referenceDate: String,
)

object BcbExchangeRateService {
    private val apiDate = DateTimeFormatter.ofPattern("MM-dd-yyyy")

    fun usdSellingRate(date: LocalDate): ExchangeRateQuote = sellingRate("USD", date)

    fun sellingRate(currency: String, date: LocalDate): ExchangeRateQuote {
        val connection = URL(quoteUrl(currency, date)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) {
                error("Banco Central indisponível (" + connection.responseCode + ")")
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val row = JSONObject(payload).getJSONArray("value").optJSONObject(0)
                ?: error("Cotação não encontrada")
            ExchangeRateQuote(
                rate = row.getDouble("cotacaoVenda").toString().toBigDecimal(),
                referenceDate = row.getString("dataHoraCotacao"),
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun quoteUrl(currency: String, date: LocalDate): String {
        require(currency == "USD" || currency == "EUR") { "Moeda não suportada" }
        val start = date.minusDays(7).format(apiDate)
        val end = date.format(apiDate)
        val resource = if (currency == "USD") {
            "CotacaoDolarPeriodo(dataInicial=@dataInicial,dataFinalCotacao=@dataFinalCotacao)"
        } else {
            "CotacaoMoedaPeriodo(moeda=@moeda,dataInicial=@dataInicial,dataFinalCotacao=@dataFinalCotacao)"
        }
        val currencyParameter = if (currency == "EUR") "?@moeda='EUR'&" else "?"
        return "https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/" +
            resource + currencyParameter +
            "@dataInicial='$start'&@dataFinalCotacao='$end'" +
            "&%24orderby=dataHoraCotacao%20desc&%24top=1&%24format=json"
    }
}
