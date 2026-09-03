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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerField(
    value: String,
    label: String,
    allowClear: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var showingPicker by remember { mutableStateOf(false) }
    val selectedDate = value.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs)) {
        OutlinedButton(
            onClick = { showingPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "$label: " + (selectedDate?.format(SHORT_DATE_FORMATTER)
                    ?: "Selecionar data")
            )
        }
        if (allowClear && value.isNotBlank()) {
            TextButton(onClick = { onValueChange("") }) { Text("Limpar $label") }
        }
    }
    if (showingPicker) {
        val initialMillis = selectedDate
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showingPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onValueChange(
                            Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                        )
                    }
                    showingPicker = false
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { showingPicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
internal fun MonthSelector(
    selectedMonth: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(selectedMonth, swipeThreshold) {
                    var horizontalDistance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { horizontalDistance = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            horizontalDistance += dragAmount
                        },
                        onDragEnd = {
                            when {
                                horizontalDistance > swipeThreshold -> onPrevious()
                                horizontalDistance < -swipeThreshold -> onNext()
                            }
                        },
                    )
                }
                .padding(horizontal = FinanceSpacing.xxs, vertical = FinanceSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.Rounded.ChevronLeft,
                    contentDescription = "Mês anterior",
                )
            }
            Text(
                text = formatMonth(selectedMonth),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "Próximo mês",
                )
            }
        }
    }
}

@Composable
internal fun SummaryValue(
    label: String,
    amount: String,
    color: Color,
    modifier: Modifier = Modifier,
    prefix: String = "",
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = prefix + formatCurrency(amount),
            color = color,
            style = FinanceTextStyles.moneyMedium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun TransactionCard(
    transaction: FinancialTransactionRecord,
    onClick: () -> Unit,
    footerText: String? = null,
) {
    val isIncome = transaction.direction == FinancialTransactionDirection.INCOME
    val amountPrefix = if (isIncome) "+ " else "− "
    val semantic = MaterialTheme.financeColors
    val amountColor = if (isIncome) semantic.income else semantic.expense
    val needsCategory = transaction.category == TransactionCategory.UNCATEGORIZED
    val details = listOfNotNull(
        transaction.categoryDisplayName,
        transaction.account?.takeIf(String::isNotBlank),
    ).joinToString(" · ")

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(FinanceSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FinanceIconTile(
                icon = transaction.category.financeIcon(),
                contentDescription = transaction.categoryDisplayName,
                containerColor = if (needsCategory) {
                    semantic.pendingContainer
                } else MaterialTheme.colorScheme.primaryContainer,
                iconColor = if (needsCategory) {
                    semantic.onPendingContainer
                } else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(FinanceSpacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs),
            ) {
                Text(
                    text = transaction.description,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (
                    transaction.status == TransactionStatus.PENDING || needsCategory ||
                    transaction.categorySource == TransactionCategorySource.RULE
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (transaction.status == TransactionStatus.PENDING) {
                            FinanceStatusPill(
                                text = "Pendente",
                                foreground = semantic.onPendingContainer,
                                background = semantic.pendingContainer,
                                icon = Icons.Rounded.Schedule,
                            )
                        }
                        if (needsCategory) {
                            FinanceStatusPill(
                                text = "Classificar",
                                foreground = semantic.onPendingContainer,
                                background = semantic.pendingContainer,
                            )
                        } else if (
                            transaction.categorySource == TransactionCategorySource.RULE
                        ) {
                            FinanceStatusPill(
                                text = "Automática",
                                foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                                background = MaterialTheme.colorScheme.primaryContainer,
                                icon = Icons.Rounded.AutoAwesome,
                            )
                        }
                    }
                }
                val dates = listOfNotNull(
                    transaction.dueDate?.let { "Vence $it" },
                    transaction.plannedPaymentDate?.let { "Previsto $it" },
                    transaction.paidAt?.let { "Pago $it" },
                ).joinToString(" · ")
                if (dates.isNotBlank()) {
                    Text(
                        dates,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                footerText?.takeIf(String::isNotBlank)?.let { footer ->
                    Text(
                        footer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(FinanceSpacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = amountPrefix + formatCurrency(transaction.amount),
                    color = amountColor,
                    style = FinanceTextStyles.moneyMedium,
                    maxLines = 1,
                )
                Text(
                    text = formatTime(transaction.occurredAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CategoryPickerDialog(
    current: CategoryChoice,
    direction: FinancialTransactionDirection,
    availableCategories: List<TransactionCategory>,
    customCategories: List<String>,
    onDismiss: () -> Unit,
    onSelected: (CategoryChoice) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var newCategory by remember { mutableStateOf("") }
    var newSubcategory by remember { mutableStateOf("") }
    var creatingCategory by remember { mutableStateOf(false) }
    val presetSubcategories = mapOf(
        TransactionCategory.FOOD to listOf("Mercado", "Restaurante", "Delivery"),
        TransactionCategory.TRANSPORT to listOf("Combustível", "Transporte público", "Aplicativo"),
        TransactionCategory.HOUSING to listOf("Aluguel", "Condomínio", "Energia", "Água", "Internet"),
        TransactionCategory.HEALTH to listOf("Farmácia", "Consulta", "Exames"),
        TransactionCategory.SHOPPING to listOf("Vestuário", "Casa", "Eletrônicos"),
        TransactionCategory.EDUCATION to listOf("Mensalidade", "Cursos", "Livros"),
        TransactionCategory.LEISURE to listOf("Viagem", "Cinema", "Eventos"),
        TransactionCategory.SERVICES to listOf("Assinaturas", "Manutenção", "Profissionais"),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Selecionar categoria") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xs),
            ) {
                availableCategories.forEach { category ->
                    item(key = category.name) {
                        val selected = !creatingCategory && draft.category == category &&
                            draft.customCategory == null
                        TextButton(
                            onClick = {
                                creatingCategory = false
                                draft = CategoryChoice(category)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(category.displayName, modifier = Modifier.weight(1f))
                        }
                    }
                    if (
                        !creatingCategory && draft.category == category &&
                        draft.customCategory == null &&
                        category != TransactionCategory.UNCATEGORIZED
                    ) {
                        item {
                            Text(
                                "Subcategoria (opcional)",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = { draft = draft.copy(subcategory = null) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Sem subcategoria") }
                        }
                        presetSubcategories[category].orEmpty().forEach { name ->
                            item(key = "preset-${category.name}-$name") {
                                OutlinedButton(
                                    onClick = { draft = draft.copy(subcategory = name) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(name) }
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = newSubcategory,
                                onValueChange = { newSubcategory = it.take(40) },
                                label = { Text("Nova subcategoria") },
                                singleLine = true,
                                trailingIcon = {
                                    TextButton(
                                        enabled = newSubcategory.isNotBlank(),
                                        onClick = {
                                            draft = draft.copy(
                                                subcategory = newSubcategory.trim()
                                            )
                                            newSubcategory = ""
                                        },
                                    ) { Text("Usar") }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                customCategories.forEach { name ->
                    item(key = "custom-$name") {
                        TextButton(
                            onClick = {
                                creatingCategory = false
                                draft = CategoryChoice(
                                    category = if (
                                        direction == FinancialTransactionDirection.INCOME
                                    ) TransactionCategory.OTHER_INCOME
                                    else TransactionCategory.OTHER_EXPENSE,
                                    customCategory = name,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = !creatingCategory && draft.customCategory == name,
                                onClick = null,
                            )
                            Text(name, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (!creatingCategory && draft.customCategory != null) {
                    item {
                        OutlinedTextField(
                            value = draft.subcategory.orEmpty(),
                            onValueChange = {
                                draft = draft.copy(subcategory = it.take(40))
                            },
                            label = { Text("Subcategoria (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    TextButton(
                        onClick = { creatingCategory = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("+ Nova categoria") }
                }
                if (creatingCategory) {
                    item {
                        OutlinedTextField(
                            value = newCategory,
                            onValueChange = { newCategory = it.take(40) },
                            label = { Text("Nome da nova categoria") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = newSubcategory,
                            onValueChange = { newSubcategory = it.take(40) },
                            label = { Text("Subcategoria (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creatingCategory || newCategory.isNotBlank(),
                onClick = {
                    if (creatingCategory) {
                        val fallback =
                            if (direction == FinancialTransactionDirection.INCOME) {
                                TransactionCategory.OTHER_INCOME
                            } else {
                                TransactionCategory.OTHER_EXPENSE
                            }
                        onSelected(
                            CategoryChoice(
                                category = fallback,
                                customCategory = newCategory.trim(),
                                subcategory = newSubcategory.trim().ifBlank { null },
                            )
                        )
                    } else {
                        onSelected(draft)
                    }
                },
            ) { Text("Selecionar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun EditTransactionDialog(
    store: DiagnosticStore,
    transaction: FinancialTransactionRecord,
    onDismiss: () -> Unit,
    onSave: (
        String, TransactionCategory, String?, String?, TransactionStatus, java.math.BigDecimal,
        LocalDate?, LocalDate?, LocalDate?, Boolean, TransactionSeriesScope,
    ) -> Unit,
    onDelete: ((TransactionSeriesScope) -> Unit)? = null,
) {
    var description by remember(transaction.id) {
        mutableStateOf(transaction.description)
    }
    var selectedCategory by remember(transaction.id) {
        mutableStateOf(transaction.category)
    }
    var customCategory by remember(transaction.id) {
        mutableStateOf(transaction.customCategory)
    }
    var subcategory by remember(transaction.id) {
        mutableStateOf(transaction.subcategory)
    }
    var categoryPickerVisible by remember(transaction.id) {
        mutableStateOf(false)
    }
    var applyToFuture by remember(transaction.id) {
        mutableStateOf(false)
    }
    var selectedStatus by remember(transaction.id) {
        mutableStateOf(transaction.status)
    }
    var amount by remember(transaction.id) {
        mutableStateOf(transaction.amount.replace('.', ','))
    }
    var dueDate by remember(transaction.id) { mutableStateOf(transaction.dueDate.orEmpty()) }
    var plannedPaymentDate by remember(transaction.id) {
        mutableStateOf(transaction.plannedPaymentDate.orEmpty())
    }
    var paidAt by remember(transaction.id) { mutableStateOf(transaction.paidAt.orEmpty()) }
    var seriesScope by remember(transaction.id) {
        mutableStateOf(TransactionSeriesScope.ONLY_THIS)
    }
    val amountValue = if (',' in amount) {
        amount.replace(".", "").replace(',', '.').toBigDecimalOrNull()
    } else amount.toBigDecimalOrNull()
    val dueDateValue = dueDate.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    val paidAtValue = paidAt.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    val plannedPaymentDateValue = plannedPaymentDate.takeIf(String::isNotBlank)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    val datesValid = (dueDate.isBlank() || dueDateValue != null) &&
        (plannedPaymentDate.isBlank() || plannedPaymentDateValue != null) &&
        (paidAt.isBlank() || paidAtValue != null)
    val availableCategories = remember(transaction.direction) {
        TransactionCategory.availableFor(transaction.direction)
    }
    val customCategories = remember(transaction.direction, transaction.id) {
        store.customCategories(transaction.direction)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar movimentação") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { newValue ->
                        if (newValue.length <= DESCRIPTION_MAX_LENGTH) {
                            description = newValue
                        }
                    },
                    label = { Text("Descrição") },
                    supportingText = {
                        Text("${description.length}/$DESCRIPTION_MAX_LENGTH")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { value ->
                        amount = value.filter { it.isDigit() || it == ',' || it == '.' }
                    },
                    label = { Text("Valor") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DatePickerField(
                    dueDate, "Vencimento", allowClear = true,
                    onValueChange = { dueDate = it },
                )
                DatePickerField(
                    plannedPaymentDate, "Pagamento previsto", allowClear = true,
                    onValueChange = { plannedPaymentDate = it },
                )
                DatePickerField(
                    paidAt, "Pagamento", allowClear = true,
                    onValueChange = {
                        paidAt = it
                        selectedStatus = if (it.isNotBlank()) {
                            TransactionStatus.REALIZED
                        } else TransactionStatus.PENDING
                    },
                )
                transaction.originalAmount?.let {
                    Text(
                        "Valor original: ${formatCurrency(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (transaction.seriesId != null) {
                    Text(
                        "Série ${transaction.seriesIndex}/${transaction.seriesTotal}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Column {
                        listOf(
                            TransactionSeriesScope.ONLY_THIS to "Somente esta",
                            TransactionSeriesScope.THIS_AND_FUTURE to "Esta e as próximas",
                            TransactionSeriesScope.ALL to "Todas da série",
                        ).forEach { (scope, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = seriesScope == scope,
                                    onClick = { seriesScope = scope },
                                )
                                Text(label)
                            }
                        }
                    }
                    Text(
                        "O alcance altera descrição, categoria e valor. Datas e situação " +
                            "continuam individuais.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Categoria",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(
                    onClick = { categoryPickerVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        CategoryChoice(
                            selectedCategory,
                            customCategory,
                            subcategory,
                        ).displayName
                    )
                }
                if (categoryPickerVisible) {
                    CategoryPickerDialog(
                        current = CategoryChoice(
                            selectedCategory,
                            customCategory,
                            subcategory,
                        ),
                        direction = transaction.direction,
                        availableCategories = availableCategories,
                        customCategories = customCategories,
                        onDismiss = { categoryPickerVisible = false },
                        onSelected = { choice ->
                            selectedCategory = choice.category
                            customCategory = choice.customCategory
                            subcategory = choice.subcategory
                            categoryPickerVisible = false
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = selectedStatus == TransactionStatus.REALIZED,
                        onCheckedChange = { checked ->
                            selectedStatus = if (checked) {
                                TransactionStatus.REALIZED
                            } else {
                                paidAt = ""
                                TransactionStatus.PENDING
                            }
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (transaction.direction == FinancialTransactionDirection.INCOME) {
                            if (selectedStatus == TransactionStatus.REALIZED) {
                                "Recebido"
                            } else {
                                "Não recebido"
                            }
                        } else {
                            if (selectedStatus == TransactionStatus.REALIZED) {
                                "Pago"
                            } else {
                                "Não pago"
                            }
                        }
                    )
                }
                if (
                    TransactionCategoryRule.canApplyToFuture(
                        type = transaction.type,
                        category = selectedCategory,
                        ruleKey = transaction.ruleKey,
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = applyToFuture,
                            onCheckedChange = { applyToFuture = it },
                        )
                        Text("Aplicar a compras futuras deste estabelecimento")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        description, selectedCategory, customCategory, subcategory, selectedStatus,
                        checkNotNull(amountValue), dueDateValue, plannedPaymentDateValue,
                        paidAtValue, applyToFuture,
                        seriesScope,
                    )
                },
                enabled = description.isNotBlank() && amountValue?.signum() == 1 && datesValid,
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = { it(seriesScope) }) {
                        Text("Excluir", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        },
    )
}
