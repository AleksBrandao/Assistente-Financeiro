package br.com.assistentefinanceiro.notifications

object TransactionCsvExporter {
    fun export(transactions: List<FinancialTransactionRecord>): String {
        val header = listOf(
            "ID", "Descrição", "Direção", "Valor", "Data", "Vencimento",
            "Pagamento previsto", "Pagamento realizado", "Situação", "Categoria",
            "Conta", "Origem", "Série", "Parcela",
        )
        val lines = transactions.map { transaction ->
            listOf(
                transaction.id.toString(), transaction.description, transaction.direction.name,
                transaction.amount, transaction.occurredAt, transaction.dueDate.orEmpty(),
                transaction.plannedPaymentDate.orEmpty(), transaction.paidAt.orEmpty(),
                transaction.status.name, transaction.category.displayName,
                transaction.account.orEmpty(), transaction.origin.name,
                transaction.seriesId.orEmpty(),
                transaction.seriesIndex?.let { index -> index.toString() + "/" + transaction.seriesTotal }.orEmpty(),
            ).joinToString(";") { cell(it) }
        }
        return "\uFEFF" + (listOf(header.joinToString(";") { cell(it) }) + lines)
            .joinToString("\r\n")
    }

    private fun cell(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
}
