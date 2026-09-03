package br.com.assistentefinanceiro.notifications

object BackupTableCompatibility {
    private val introducedAtVersion = mapOf(
        "monthly_budgets" to 19,
    )

    fun requiredTables(databaseVersion: Int, currentTables: List<String>): List<String> =
        currentTables.filter { table ->
            databaseVersion >= (introducedAtVersion[table] ?: 1)
        }
}
