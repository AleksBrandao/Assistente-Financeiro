package br.com.assistentefinanceiro.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.com.assistentefinanceiro.notifications.*
import br.com.assistentefinanceiro.importing.MobillsImportAnalyzer
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import br.com.assistentefinanceiro.importing.SimpleXlsxReader
import br.com.assistentefinanceiro.ui.financeIcon
import br.com.assistentefinanceiro.ui.components.FinanceEmptyState
import br.com.assistentefinanceiro.ui.components.FinanceIconTile
import br.com.assistentefinanceiro.ui.components.FinanceNoticeCard
import br.com.assistentefinanceiro.ui.components.FinanceStatusPill
import br.com.assistentefinanceiro.ui.theme.AssistenteFinanceiroTheme
import br.com.assistentefinanceiro.ui.theme.FinanceSpacing
import br.com.assistentefinanceiro.ui.theme.FinanceTextStyles
import br.com.assistentefinanceiro.ui.theme.financeColors
import java.text.NumberFormat
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun transactionEffectiveDate(transaction: FinancialTransactionRecord): LocalDate? {
    val stored = if (transaction.status == TransactionStatus.REALIZED) {
        transaction.paidAt
    } else transaction.plannedPaymentDate ?: transaction.dueDate
    return stored?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: runCatching { LocalDateTime.parse(transaction.occurredAt).toLocalDate() }.getOrNull()
}

internal fun granularTransactions(store: DiagnosticStore): List<FinancialTransactionRecord> =
    store.recentTransactions(10_000)

internal fun consolidatedTransactions(store: DiagnosticStore): List<FinancialTransactionRecord> {
    val transactions = store.recentTransactions(10_000)
    val cards = store.financialAccounts().filter {
        it.type == FinancialAccountType.CREDIT_CARD
    }
    val cardIds = cards.map { it.id }.toSet()
    val invoices = cards.flatMap { account ->
        store.creditCardInvoices(account.id).mapNotNull { invoice ->
            val due = invoice.dueDate ?: return@mapNotNull null
            if (invoice.total.signum() == 0) return@mapNotNull null
            val paidAt = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                store.invoicePayments(invoice).maxOfOrNull { it.paidAt }
            } else null
            FinancialTransactionRecord(
                id = -invoice.id,
                sourceEventId = null,
                direction = if (invoice.total.signum() < 0) {
                    FinancialTransactionDirection.INCOME
                } else FinancialTransactionDirection.EXPENSE,
                type = if (invoice.total.signum() < 0) {
                    FinancialTransactionType.IMPORTED_INCOME
                } else FinancialTransactionType.IMPORTED_EXPENSE,
                amount = invoice.total.abs().toPlainString(),
                occurredAt = due.atStartOfDay().toString(),
                description = "Fatura ${account.name}",
                sourcePackage = "credit-card-invoice",
                status = if (invoice.status == CreditCardInvoiceStatus.PAID) {
                    TransactionStatus.REALIZED
                } else TransactionStatus.PENDING,
                dueDate = due.toString(),
                paidAt = paidAt?.toString(),
            )
        }
    }
    return transactions.filterNot { transaction ->
        transaction.type == FinancialTransactionType.CARD_PURCHASE ||
            (transaction.accountId != null && transaction.accountId in cardIds)
    } + invoices
}
