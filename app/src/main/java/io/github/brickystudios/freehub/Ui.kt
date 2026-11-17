package io.github.brickystudios.freehub

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import java.util.Locale

/** The app's language: null follows the phone (German phones get German, all others English). */
object Lang {
    var chosen: String? = null
    fun init(c: Context) { chosen = Prefs.lang(c) }
}

fun locale(): Locale = Lang.chosen?.let(Locale::forLanguageTag) ?: Locale.getDefault()

fun tr(de: String, en: String) = if (locale().language == "de") de else en

/** numbers in the app's language: 3,33 in German, 3.33 in English */
fun String.fmt(vararg args: Any?): String = String.format(locale(), this, *args)

/** Colours as tokens. The accent is anodised pink, the colour hub makers anodise their freehub bodies in. */
data class Palette(
    val bg: Color, val surface: Color, val raised: Color, val line: Color,
    /** unused cogs, empty heatmap cells, battery shell */
    val track: Color,
    val ink: Color, val muted: Color, val faint: Color,
    val accent: Color, val onAccent: Color, val warn: Color,
    /** tint of the floating glass: see-through where Android can blur behind it */
    val glass: Color,
)

val Carbon = Palette(
    bg = Color(0xFF0D0E10), surface = Color(0xFF16181B), raised = Color(0xFF1F2226), line = Color(0xFF2B2F34),
    track = Color(0xFF262A2F),
    ink = Color(0xFFF3F2EE), muted = Color(0xFFA0A4AA), faint = Color(0xFF62676E),
    accent = Color(0xFFFF4D94), onAccent = Color(0xFF1A0710), warn = Color(0xFFFF9F43),
    glass = Color(0xA61F2226),
)

val Paper = Palette(
    bg = Color(0xFFF3F2EE), surface = Color(0xFFFFFFFF), raised = Color(0xFFE9E7E1), line = Color(0xFFD9D6CE),
    track = Color(0xFFD6D3CB),
    ink = Color(0xFF121316), muted = Color(0xFF575B62), faint = Color(0xFF8E9298),
    accent = Color(0xFFD81B6A), onAccent = Color(0xFFFFFFFF), warn = Color(0xFFB4410B),
    glass = Color(0xB8FFFFFF),
)

val LocalPalette = staticCompositionLocalOf { Carbon }
val P: Palette @Composable get() = LocalPalette.current

val Numerals = FontFamily(
    Font(R.font.sairacondensed_bold, FontWeight.Bold),
    Font(R.font.sairacondensed_extrabold, FontWeight.ExtraBold),
)
val Body = FontFamily(
    Font(R.font.sairasemicondensed_regular, FontWeight.Normal),
    Font(R.font.sairasemicondensed_medium, FontWeight.Medium),
    Font(R.font.sairasemicondensed_semibold, FontWeight.SemiBold),
)

@Composable
fun FreehubTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) Carbon else Paper, content = content)
}

/** iOS-style continuous corners: the curve eases into the straight edge instead of meeting it at a kink. */
val Card: Shape = RoundedRectangle(22.dp)
val Pill: Shape = Capsule()

/** What scrolls behind the floating chrome, for the glass to blur and bend. */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Liquid Glass (github.com/Kyant0/AndroidLiquidGlass, Apache-2.0): blur from Android 12, refraction at the rim
 * from Android 13. Older phones only get the tint, so it turns nearly solid there to keep text readable.
 * Only for chrome that floats over content; glass on glass loses all legibility.
 */
@Composable
fun Modifier.glass(shape: Shape = Pill): Modifier {
    val tint = if (Build.VERSION.SDK_INT >= 31) P.glass else P.raised.copy(alpha = 0.96f)
    val backdrop = LocalBackdrop.current ?: return background(tint, shape)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(22.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        },
        onDrawSurface = { drawRect(tint) },
    )
}

/** Space at the end of a screen so its last line can scroll up past the floating tab bar. */
@Composable
fun TabBarRoom() = Spacer(Modifier.navigationBarsPadding().height(96.dp))

/** Small caps-style label above a value. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = P.muted) = Text(
    text.uppercase(), modifier,
    style = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.09.em, color = color),
)

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color = P.ink, size: TextUnit = 16.sp, weight: FontWeight = FontWeight.Normal, align: TextAlign? = null) = Text(
    text, modifier,
    style = TextStyle(fontFamily = Body, fontWeight = weight, fontSize = size, lineHeight = size * 1.35f, color = color, textAlign = align ?: TextAlign.Unspecified),
)

@Composable
fun Num(text: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = P.ink, weight: FontWeight = FontWeight.ExtraBold) = Text(
    text, modifier,
    style = TextStyle(fontFamily = Numerals, fontWeight = weight, fontSize = size, lineHeight = size * 0.95f, color = color, letterSpacing = (-0.01).em),
)

/** A value with its label underneath, the way a bike computer shows a field. */
@Composable
fun Field(value: String, label: String, modifier: Modifier = Modifier, size: TextUnit = 34.sp, color: Color = P.ink) {
    Column(modifier) {
        Num(value, size, color = color, weight = FontWeight.Bold)
        Label(label)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, pad: Dp = 18.dp, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(P.surface, Card).padding(pad)) { content() }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().padding(vertical = 0.dp).heightIn(min = 1.dp, max = 1.dp).background(P.line))

/** Primary action: a pink capsule; secondary: a quiet filled one. 52 dp tall for gloves. */
@Composable
fun Action(text: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 52.dp)
            .pressable(source)
            .background(if (primary) P.accent else P.raised, Pill)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.em, color = if (primary) P.onAccent else P.ink),
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).background(color, CircleShape))

/** A settings row: text left, control right, the whole row tappable. */
@Composable
fun SettingRow(title: String, note: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BodyText(title, weight = FontWeight.Medium)
            if (note != null) BodyText(note, color = P.muted, size = 14.sp)
        }
        trailing()
    }
}

/** A one-line text field in the app's style: label above, a framed slab like a [Panel]. */
@Composable
fun Input(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, digits: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Label(label)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = Body, fontSize = 17.sp, color = P.ink),
            cursorBrush = SolidColor(P.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (digits) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .background(P.surface, RoundedCornerShape(6.dp)).border(1.dp, P.line, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 15.dp),
        )
    }
}

/** On/off switch in iOS proportions; the thumb slides with a critically damped spring, no bounce. */
@Composable
fun Toggle(on: Boolean) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, if (reducedMotion()) snap() else spring(1f, Spring.StiffnessMediumLow), label = "toggle")
    Box(Modifier.size(width = 51.dp, height = 31.dp).background(if (on) P.accent else P.track, Pill).padding(2.dp)) {
        Box(Modifier.offset(x = x).size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    /** wheel circumference in mm; 2136 = 700x28C */
    fun wheel(c: Context) = p(c).getInt("wheel", 2136)
    fun setWheel(c: Context, mm: Int) = p(c).edit().putInt("wheel", mm).apply()
    fun background(c: Context) = p(c).getBoolean("background", true)
    fun setBackground(c: Context, on: Boolean) = p(c).edit().putBoolean("background", on).apply()
    /** a tick per cog while the app is open */
    fun haptics(c: Context) = p(c).getBoolean("haptics", true)
    fun setHaptics(c: Context, on: Boolean) = p(c).edit().putBoolean("haptics", on).apply()
    fun lang(c: Context) = p(c).getString("lang", null)
    fun setLang(c: Context, lang: String?) = p(c).edit().putString("lang", lang).apply()
    /** crank and cassette pattern numbers picked by hand; -1 = take what the derailleur reports */
    fun rings(c: Context) = p(c).getInt("rings", -1)
    fun setRings(c: Context, pattern: Int) = p(c).edit().putInt("rings", pattern).apply()
    fun cogs(c: Context) = p(c).getInt("cogs", -1)
    fun setCogs(c: Context, pattern: Int) = p(c).edit().putInt("cogs", pattern).apply()
    /** the first-start setup ran through (or was skipped) */
    fun onboarded(c: Context) = p(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, done: Boolean) = p(c).edit().putBoolean("onboarded", done).apply()
    /** developer options: Bluetooth capture, read all, read memory */
    fun dev(c: Context) = p(c).getBoolean("dev", false)
    fun setDev(c: Context, on: Boolean) = p(c).edit().putBoolean("dev", on).apply()
    fun updates(c: Context) = p(c).getBoolean("updates", true)
    fun setUpdates(c: Context, on: Boolean) = p(c).edit().putBoolean("updates", on).apply()

    /** the first gear report of the last connection, raw, so offline screens know rings and cogs */
    fun lastGears(c: Context): Gears? = reportedGears(c)?.withPicked(rings(c), cogs(c))
    /** as the derailleur sent it, without the teeth picked by hand */
    fun reportedGears(c: Context): Gears? = rawGears(c)?.let { hex ->
        Sbi.periodic(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
    /** the same report as hex, shown under Bike so a rider can send it when the teeth come out wrong */
    fun rawGears(c: Context): String? = p(c).getString("gears", null)
    fun setLastGears(c: Context, raw: ByteArray) = p(c).edit().putString("gears", raw.joinToString("") { "%02x".format(it) }).apply()
}

/** "38 min", "1:24 h", and whole hours from ten on, where minutes stop mattering */
fun duration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "$min min"
        min < 600 -> "%d:%02d h".fmt(min / 60, min % 60)
        else -> "${count(min / 60)} h"
    }
}

/** "≈ 820 km": an estimate, rounded to ten so it does not pretend to be exact */
fun approxKm(km: Double) = "≈ " + count((Math.round(km / 10) * 10)) + " km"

/** 1234 → "1.234" in German, "1,234" in English */
fun count(n: Long): String = "%,d".fmt(n)
