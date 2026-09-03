package br.com.assistentefinanceiro.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalActivity
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.ui.graphics.vector.ImageVector
import br.com.assistentefinanceiro.notifications.TransactionCategory

fun TransactionCategory.financeIcon(): ImageVector = when (this) {
    TransactionCategory.UNCATEGORIZED -> Icons.Rounded.HelpOutline
    TransactionCategory.FOOD -> Icons.Rounded.Restaurant
    TransactionCategory.TRANSPORT -> Icons.Rounded.DirectionsCar
    TransactionCategory.HOUSING -> Icons.Rounded.Home
    TransactionCategory.HEALTH -> Icons.Rounded.MedicalServices
    TransactionCategory.SHOPPING -> Icons.Rounded.ShoppingBag
    TransactionCategory.EDUCATION -> Icons.Rounded.School
    TransactionCategory.LEISURE -> Icons.Rounded.LocalActivity
    TransactionCategory.SERVICES -> Icons.Rounded.Handyman
    TransactionCategory.SALARY -> Icons.Rounded.Payments
    TransactionCategory.TRANSFER_IN -> Icons.Rounded.SwapHoriz
    TransactionCategory.REFUND -> Icons.Rounded.Undo
    TransactionCategory.INVESTMENT_INCOME -> Icons.Rounded.TrendingUp
    TransactionCategory.OTHER_EXPENSE,
    TransactionCategory.OTHER_INCOME,
    -> Icons.Rounded.Category
}
