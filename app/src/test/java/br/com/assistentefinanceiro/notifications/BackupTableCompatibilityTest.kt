package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTableCompatibilityTest {
    @Test
    fun acceptsBackupCreatedBeforeMonthlyBudgetsTable() {
        val tables = listOf("transactions", "financial_accounts", "monthly_budgets")

        assertEquals(
            listOf("transactions", "financial_accounts"),
            BackupTableCompatibility.requiredTables(18, tables),
        )
        assertEquals(tables, BackupTableCompatibility.requiredTables(19, tables))
    }
}
