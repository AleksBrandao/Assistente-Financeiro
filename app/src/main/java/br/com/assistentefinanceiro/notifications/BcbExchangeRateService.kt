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

    fun usdSellingRate(date: LocalDate): ExchangeRateQuote {
        val start = date.minusDays(7).format(apiDate)
        val end = date.format(apiDate)
        val endpoint =
            "https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/" +
                "CotacaoDolarPeriodo(dataInicial=@dataInicial,dataFinalCotacao=@dataFinalCotacao)" +
                "?@dataInicial='$start'&@dataFinalCotacao='$end'" +
                "&%24orderby=dataHoraCotacao%20desc&%24top=1&%24format=json"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
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
}
