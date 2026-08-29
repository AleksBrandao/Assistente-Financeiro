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
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Eventos autorizados", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { store.clearEvents(); refresh++ }) { Text("Limpar") }
                } }
                if (events.isEmpty()) item { Text("Nenhum evento armazenado.") }
                items(events, key = { it.id }) { event ->
                    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(event.body)
                        Text(if (event.parsed) "Reconhecida: final ${event.cardLastFour} · R$ ${event.amount} · ${event.merchant}" else "Aguardando regra", color = if (event.parsed) Color(0xFF0A7D65) else Color(0xFFBA3B46))
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
}

