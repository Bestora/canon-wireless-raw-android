package de.bestora.canonwirelessrawandroid.ui

import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A contact sheet on a loupe table: neutral grey so the photos' colours read true, paper-white
 * type, and one colour with a job, the grease-pencil red that marks frames and the main action.
 * Dark only, like photo tools: a bright UI around photos skews how they look.
 */
object Palette {
    val LoupeGrey = Color(0xFF262626)
    val Sheet = Color(0xFF303030)
    val Raised = Color(0xFF3B3B3B)
    val Paper = Color(0xFFEDEBE7)
    val Graphite = Color(0xFFA9A6A0)
    val GreasePencil = Color(0xFFD83A2F)
    /** Problems only (connection lost); kept apart from the red that means "marked". */
    val Amber = Color(0xFFF0B04A)
}

/** The condensed face every Android ships: lettering like a camera's display and a film's edge print. */
private val Condensed = FontFamily(androidx.compose.ui.text.font.Typeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL)))

private val type = Typography().run {
    copy(
        displaySmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp),
        headlineSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
        titleSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    )
}

private val scheme = darkColorScheme(
    primary = Palette.GreasePencil,
    onPrimary = Color.White,
    secondary = Palette.Graphite,
    onSecondary = Palette.LoupeGrey,
    background = Palette.LoupeGrey,
    onBackground = Palette.Paper,
    surface = Palette.LoupeGrey,
    onSurface = Palette.Paper,
    surfaceVariant = Palette.Sheet,
    onSurfaceVariant = Palette.Graphite,
    surfaceContainer = Palette.Sheet,
    surfaceContainerHigh = Palette.Raised,
    secondaryContainer = Palette.Raised,
    onSecondaryContainer = Palette.Paper,
    outline = Palette.Graphite,
    outlineVariant = Palette.Raised,
    error = Palette.Amber,
    onError = Palette.LoupeGrey,
)

private val ButtonShape = RoundedCornerShape(8.dp)
private val ButtonHeight = 50.dp

/** The screen's main action, in grease-pencil red. One per screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = ButtonShape, modifier = modifier.heightIn(min = ButtonHeight)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Everything else that needs to look like a button: quiet outline, paper text. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(1.dp, if (enabled) Palette.Graphite.copy(alpha = 0.6f) else Palette.Raised),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Paper),
        modifier = modifier.heightIn(min = ButtonHeight),
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
