package br.com.assistentefinanceiro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EDE4),
    onPrimaryContainer = Color(0xFF05201A),
    secondary = Color(0xFF52665F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEAE5),
    onSecondaryContainer = Color(0xFF14201C),
    tertiary = Color(0xFF8A5C13),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE1A8),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF17211D),
    surface = Color.White,
    onSurface = Color(0xFF17211D),
    surfaceVariant = Color(0xFFE8EEEB),
    onSurfaceVariant = Color(0xFF5D6B65),
    outline = Color(0xFF788680),
    outlineVariant = Color(0xFFD5DFDA),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E5),
    onErrorContainer = Color(0xFF8A1C13),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BD5C3),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF164E42),
    onPrimaryContainer = Color(0xFFB6F1E2),
    secondary = Color(0xFFBACBC4),
    onSecondary = Color(0xFF26332F),
    secondaryContainer = Color(0xFF34443E),
    onSecondaryContainer = Color(0xFFD6E8E1),
    tertiary = Color(0xFFF1C27B),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF5E420E),
    onTertiaryContainer = Color(0xFFFFE0A8),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE5EAE7),
    surface = Color(0xFF151D1A),
    onSurface = Color(0xFFE5EAE7),
    surfaceVariant = Color(0xFF25302C),
    onSurfaceVariant = Color(0xFFBBC7C1),
    outline = Color(0xFF89968F),
    outlineVariant = Color(0xFF3D4944),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5A1B18),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Immutable
data class FinanceSemanticColors(
    val income: Color,
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    val expense: Color,
    val expenseContainer: Color,
    val onExpenseContainer: Color,
    val pending: Color,
    val pendingContainer: Color,
    val onPendingContainer: Color,
    val realized: Color,
    val realizedContainer: Color,
    val onRealizedContainer: Color,
)

private val LightSemanticColors = FinanceSemanticColors(
    income = Color(0xFF087A55),
    incomeContainer = Color(0xFFD8F3E8),
    onIncomeContainer = Color(0xFF07543D),
    expense = Color(0xFFB23A48),
    expenseContainer = Color(0xFFFBE2E5),
    onExpenseContainer = Color(0xFF7A2532),
    pending = Color(0xFFA66500),
    pendingContainer = Color(0xFFFFF0D5),
    onPendingContainer = Color(0xFF704100),
    realized = Color(0xFF176B5B),
    realizedContainer = Color(0xFFC9EDE4),
    onRealizedContainer = Color(0xFF0A493E),
)

private val DarkSemanticColors = FinanceSemanticColors(
    income = Color(0xFF62D4A8),
    incomeContainer = Color(0xFF123B2F),
    onIncomeContainer = Color(0xFF8CE3BE),
    expense = Color(0xFFFF8995),
    expenseContainer = Color(0xFF4A2027),
    onExpenseContainer = Color(0xFFFFB3BB),
    pending = Color(0xFFF3BC58),
    pendingContainer = Color(0xFF3E2E10),
    onPendingContainer = Color(0xFFFFD68A),
    realized = Color(0xFF8BD5C3),
    realizedContainer = Color(0xFF164E42),
    onRealizedContainer = Color(0xFFB6F1E2),
)

private val LocalFinanceSemanticColors = staticCompositionLocalOf {
    LightSemanticColors
}

val MaterialTheme.financeColors: FinanceSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalFinanceSemanticColors.current

object FinanceSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 40.dp
    val huge = 48.dp
}

object FinanceTextStyles {
    val moneyHero = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    )
    val moneyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontFeatureSettings = "tnum",
    )
    val moneyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum",
    )
}

val FinanceTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = FinanceTextStyles.moneyHero,
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = FinanceTextStyles.moneyLarge,
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = FinanceTextStyles.moneyMedium,
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

private val FinanceShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AssistenteFinanceiroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalFinanceSemanticColors provides if (darkTheme) {
            DarkSemanticColors
        } else {
            LightSemanticColors
        },
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = FinanceTypography,
            shapes = FinanceShapes,
            content = content,
        )
    }
}
