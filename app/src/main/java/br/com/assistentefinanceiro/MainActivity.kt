package br.com.assistentefinanceiro

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.notifications.*
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0A7D65))) { DiagnosticScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DiagnosticScreen() {
        val store = remember { DiagnosticStore(applicationContext) }
        val preferences = remember { BankPackagePreferences(applicationContext) }
        var refresh by remember { mutableIntStateOf(0) }
        val allowed = remember(refresh) { preferences.allowedPackages() }
        val candidates = remember(refresh) { store.candidates() }
        val transactions = remember(refresh) { store.recentTransactions() }
        val events = remember(refresh) { store.recentEvents() }
        val enabled = remember(refresh) { notificationAccessEnabled() }

        Scaffold(topBar = { TopAppBar(title = { Text("Diagnóstico financeiro") }) }) { padding ->
            LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (enabled) "Acesso às notificações ativo" else "Acesso às notificações desativado", style = MaterialTheme.typography.titleMedium)
                        Text("O conteúdo só é armazenado depois que você autoriza um aplicativo específico.")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Configurar acesso") }
                        OutlinedButton(onClick = { refresh++ }) { Text("Atualizar") }
                    } }
                }
                item { Text("Aplicativos detectados", style = MaterialTheme.typography.titleLarge) }
                if (candidates.isEmpty()) item { Text("Após ativar o acesso, aguarde uma notificação do banco e toque em Atualizar.") }
                items(candidates) { (packageName, label) ->
                    Card { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(label); Text(packageName, style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = packageName in allowed, onCheckedChange = {
                            if (it) preferences.allow(packageName) else preferences.remove(packageName); refresh++
                        })
                    } }
                }
                item { Text("Movimentações estruturadas", style = MaterialTheme.typography.titleLarge) }
                if (transactions.isEmpty()) item { Text("Nenhuma movimentação reconhecida.") }
                items(transactions, key = { "transaction-${it.id}" }) { transaction ->
                    val isIncome = transaction.direction == FinancialTransactionDirection.INCOME
                    val amountPrefix = if (isIncome) "+ " else "- "
                    val amountColor = if (isIncome) Color(0xFF0A7D65) else Color(0xFFBA3B46)
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    transaction.description,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    amountPrefix + formatCurrency(transaction.amount),
                                    color = amountColor,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                when (transaction.type) {
                                    FinancialTransactionType.CARD_PURCHASE -> "Compra no cartão"
                                    FinancialTransactionType.PIX_RECEIVED -> "PIX recebido"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                transaction.occurredAt.replace('T', ' '),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Eventos autorizados", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { store.clearEvents(); refresh++ }) { Text("Limpar") }
                } }
                if (events.isEmpty()) item { Text("Nenhum evento armazenado.") }
                items(events, key = { it.id }) { event ->
                    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(event.body)
                        val statusText = when (event.classification) {
                            NotificationClassification.TRANSACTION -> when (event.transactionType) {
                                FinancialTransactionType.CARD_PURCHASE ->
                                    "Reconhecida: final ${event.cardLastFour} · ${formatCurrency(event.amount)} · ${event.merchant}"
                                FinancialTransactionType.PIX_RECEIVED ->
                                    "Entrada reconhecida: PIX · ${formatCurrency(event.amount)}"
                                null -> "Transação reconhecida"
                            }
                            NotificationClassification.IGNORED_PROMOTION ->
                                "Ignorada: ${event.classificationReason ?: "promoção"}"
                            NotificationClassification.PENDING_RULE -> "Aguardando regra"
                        }
                        val statusColor = when (event.classification) {
                            NotificationClassification.TRANSACTION -> Color(0xFF0A7D65)
                            NotificationClassification.IGNORED_PROMOTION -> MaterialTheme.colorScheme.onSurfaceVariant
                            NotificationClassification.PENDING_RULE -> Color(0xFFBA3B46)
                        }
                        Text(statusText, color = statusColor)
                    } }
                }
            }
        }
    }

    private fun notificationAccessEnabled(): Boolean {
        val component = ComponentName(this, FinanceNotificationListener::class.java)
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(":")?.any { ComponentName.unflattenFromString(it) == component } == true
    }

    private fun formatCurrency(amount: String?): String =
        amount?.toBigDecimalOrNull()?.let {
            NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(it)
        } ?: "valor indisponível"
}
