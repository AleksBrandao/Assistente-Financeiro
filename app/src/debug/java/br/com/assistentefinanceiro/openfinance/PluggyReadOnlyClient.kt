package br.com.assistentefinanceiro.openfinance

import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class PluggySandboxAccountPreview(
    val account: PluggyAccountSnapshot,
    val transactionCount: Int,
    val postedCount: Int,
    val pendingCount: Int,
    val installmentCount: Int,
    val pixCount: Int,
    val transactions: List<PluggyTransactionSnapshot>,
    val bills: List<PluggyBillSnapshot>,
)

internal data class PluggySandboxPreview(
    val itemStatus: String,
    val executionStatus: String?,
    val accounts: List<PluggySandboxAccountPreview>,
)

internal class PluggyApiException(
    val httpStatus: Int,
    val codeDescription: String?,
    override val message: String,
) : Exception(message)

/**
 * Debug-only, read-only Pluggy client.
 *
 * The apiKey is supplied at runtime and never persisted by this class. The clientSecret is not
 * accepted at all, so it cannot accidentally be embedded in the Android application.
 */
internal class PluggyReadOnlyClient(
    private val baseUrl: String = "https://api.pluggy.ai",
) {
    fun fetchPreview(apiKey: String, itemId: String): PluggySandboxPreview {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        validateUuid(itemId, "itemId")

        val item = getJson("/items/$itemId", apiKey)
        val accounts = fetchAccounts(apiKey, itemId)
        val accountPreviews = accounts.map { account ->
            val transactions = fetchAllTransactions(apiKey, account.externalId)
            val bills = if (account.type == PluggyAccountType.CREDIT) {
                fetchBills(apiKey, account.externalId)
            } else {
                emptyList()
            }
            PluggySandboxAccountPreview(
                account = account,
                transactionCount = transactions.size,
                postedCount = transactions.count { it.status == PluggyTransactionStatus.POSTED },
                pendingCount = transactions.count { it.status == PluggyTransactionStatus.PENDING },
                installmentCount = transactions.count { it.isInstallment },
                pixCount = transactions.count {
                    it.operationType.equals("PIX", ignoreCase = true) ||
                        it.paymentMethod.equals("PIX", ignoreCase = true)
                },
                transactions = transactions,
                bills = bills,
            )
        }

        return PluggySandboxPreview(
            itemStatus = item.optNullableString("status") ?: "UNKNOWN",
            executionStatus = item.optNullableString("executionStatus"),
            accounts = accountPreviews,
        )
    }

    private fun fetchAccounts(apiKey: String, itemId: String): List<PluggyAccountSnapshot> {
        val root = getJson("/accounts?itemId=$itemId", apiKey)
        return root.getJSONArray("results").mapAccounts(::parseAccount)
    }

    private fun fetchBills(apiKey: String, accountId: String): List<PluggyBillSnapshot> {
        validateUuid(accountId, "accountId")
        val root = getJson("/bills?accountId=$accountId", apiKey)
        return root.getJSONArray("results").mapBills { bill ->
            parseBill(bill, accountId)
        }
    }

    private fun fetchAllTransactions(
        apiKey: String,
        accountId: String,
        maxPages: Int = 100,
    ): List<PluggyTransactionSnapshot> {
        validateUuid(accountId, "accountId")
        val transactions = mutableListOf<PluggyTransactionSnapshot>()
        var suffix = "?accountId=$accountId"
        val seenCursors = mutableSetOf<String>()
        repeat(maxPages) {
            val root = getJson("/v2/transactions$suffix", apiKey)
            transactions += root.getJSONArray("results").mapTransactions { transaction ->
                parseTransaction(transaction, accountId)
            }
            val next = root.optNullableString("next") ?: return transactions
            require(next.startsWith("?")) { "Invalid Pluggy pagination cursor" }
            if (!seenCursors.add(next)) return transactions
            suffix = next
        }
        throw IllegalStateException("Pluggy pagination exceeded $maxPages pages")
    }

    private fun getJson(path: String, apiKey: String): JSONObject {
        require(path.startsWith("/")) { "Only relative Pluggy API paths are allowed" }
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-KEY", apiKey)
            instanceFollowRedirects = false
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(body) }.getOrNull()
                throw PluggyApiException(
                    httpStatus = status,
                    codeDescription = error?.optNullableString("codeDescription"),
                    message = error?.optNullableString("message") ?: "Pluggy HTTP $status",
                )
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAccount(json: JSONObject): PluggyAccountSnapshot {
        val type = when (json.getString("type")) {
            "BANK" -> PluggyAccountType.BANK
            "CREDIT" -> PluggyAccountType.CREDIT
            else -> error("Unsupported Pluggy account type")
        }
        return PluggyAccountSnapshot(
            externalId = json.getString("id"),
            type = type,
            subtype = json.optNullableString("subtype"),
            name = json.optNullableString("name")?.trim().orEmpty().ifBlank { "Conta Pluggy" },
            currencyCode = json.optNullableString("currencyCode") ?: "BRL",
            balance = json.requireBigDecimal("balance"),
            bankData = json.optJSONObjectOrNull("bankData")?.let { bank ->
                PluggyBankData(
                    closingBalance = bank.optBigDecimal("closingBalance"),
                    overdraftContractedLimit = bank.optBigDecimal("overdraftContractedLimit"),
                    overdraftUsedLimit = bank.optBigDecimal("overdraftUsedLimit"),
                )
            },
            creditData = json.optJSONObjectOrNull("creditData")?.let { credit ->
                PluggyCreditData(
                    creditLimit = credit.optBigDecimal("creditLimit"),
                    availableCreditLimit = credit.optBigDecimal("availableCreditLimit"),
                    balanceDueDate = credit.optLocalDate("balanceDueDate"),
                    minimumPayment = credit.optBigDecimal("minimumPayment"),
                )
            },
        )
    }

    private fun parseBill(json: JSONObject, accountId: String): PluggyBillSnapshot =
        PluggyBillSnapshot(
            externalId = json.getString("id"),
            accountExternalId = json.optNullableString("accountId") ?: accountId,
            dueDate = checkNotNull(json.optLocalDate("dueDate")) { "Bill without dueDate" },
            closingDate = json.optLocalDate("billClosingDate"),
            totalAmount = json.requireBigDecimal("totalAmount"),
            minimumPaymentAmount = json.optBigDecimal("minimumPaymentAmount"),
            allowsInstallments = json.optBooleanOrNull("allowsInstallments"),
        )

    private fun parseTransaction(
        json: JSONObject,
        accountId: String,
    ): PluggyTransactionSnapshot {
        val card = json.optJSONObjectOrNull("creditCardMetadata")
        val payment = json.optJSONObjectOrNull("paymentData")
        return PluggyTransactionSnapshot(
            externalId = json.getString("id"),
            accountExternalId = json.optNullableString("accountId") ?: accountId,
            amount = json.requireBigDecimal("amount"),
            date = Instant.parse(json.getString("date")),
            purchaseDate = card?.optInstant("purchaseDate"),
            direction = when (json.getString("type")) {
                "CREDIT" -> PluggyTransactionDirection.CREDIT
                "DEBIT" -> PluggyTransactionDirection.DEBIT
                else -> error("Unsupported Pluggy transaction direction")
            },
            status = when (json.getString("status")) {
                "POSTED" -> PluggyTransactionStatus.POSTED
                "PENDING" -> PluggyTransactionStatus.PENDING
                else -> error("Unsupported Pluggy transaction status")
            },
            description = json.optNullableString("description").orEmpty(),
            category = json.optNullableString("category"),
            categoryId = json.optNullableString("categoryId"),
            operationType = json.optNullableString("operationType"),
            paymentMethod = payment?.optNullableString("paymentMethod"),
            installmentNumber = card?.optIntOrNull("installmentNumber"),
            totalInstallments = card?.optIntOrNull("totalInstallments"),
            totalAmount = card?.optBigDecimal("totalAmount"),
            billForecastDate = card?.optYearMonth("billForecastDate"),
            billExternalId = card?.optNullableString("billId"),
            cardLastFour = PluggyDataSanitizer.cardLastFour(card?.optNullableString("cardNumber")),
        )
    }

    private fun validateUuid(value: String, field: String) {
        runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalArgumentException("$field must be a valid UUID") }
    }
}

private fun JSONArray.mapAccounts(
    transform: (JSONObject) -> PluggyAccountSnapshot,
): List<PluggyAccountSnapshot> = List(length()) { index -> transform(getJSONObject(index)) }

private fun JSONArray.mapBills(
    transform: (JSONObject) -> PluggyBillSnapshot,
): List<PluggyBillSnapshot> = List(length()) { index -> transform(getJSONObject(index)) }

private fun JSONArray.mapTransactions(
    transform: (JSONObject) -> PluggyTransactionSnapshot,
): List<PluggyTransactionSnapshot> = List(length()) { index -> transform(getJSONObject(index)) }

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun JSONObject.optJSONObjectOrNull(name: String): JSONObject? =
    if (!has(name) || isNull(name)) null else optJSONObject(name)

private fun JSONObject.requireBigDecimal(name: String): BigDecimal =
    checkNotNull(optBigDecimal(name)) { "Missing numeric field $name" }

private fun JSONObject.optBigDecimal(name: String): BigDecimal? {
    if (!has(name) || isNull(name)) return null
    return get(name).toString().toBigDecimalOrNull()
}

private fun JSONObject.optLocalDate(name: String): LocalDate? =
    optNullableString(name)?.take(10)?.let(LocalDate::parse)

private fun JSONObject.optInstant(name: String): Instant? =
    optNullableString(name)?.let(Instant::parse)

private fun JSONObject.optYearMonth(name: String): YearMonth? =
    optNullableString(name)?.let(YearMonth::parse)

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else getBoolean(name)
