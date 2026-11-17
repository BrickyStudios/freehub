package io.github.brickystudios.freehub

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** "Remove animations" in the system settings: everything stands still, nothing slides or spins. */
@Composable
fun reducedMotion(): Boolean {
    val c = LocalContext.current
    return remember { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The freehub's ratchet ring with its pawl. While [coasting] the ring turns under a still pawl,
 * the way a rear hub freewheels; at rest it is the app's mark.
 */
@Composable
fun Ratchet(modifier: Modifier, coasting: Boolean = false, ring: Color = P.ink, pawl: Color = P.accent) {
    val still = reducedMotion() || !coasting
    val turn = if (still) 0f else {
        val t = rememberInfiniteTransition(label = "coast")
        // constant motion is the one place for linear easing
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
    }
    Canvas(modifier) {
        val r = size.minDimension / 2
        val teeth = 24
        val step = (2 * PI / teeth).toFloat()
        val ringPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(center, r))
            fun at(rad: Float, a: Float) = center + Offset(cos(a) * rad, sin(a) * rad)
            val a0 = (-PI / 2).toFloat()
            at(r * 0.84f, a0).let { moveTo(it.x, it.y) }
            for (i in 1..teeth) {
                val a = a0 + i * step
                at(r * 0.72f, a - step * 0.04f).let { lineTo(it.x, it.y) }
                at(r * 0.84f, a).let { lineTo(it.x, it.y) }
            }
            close()
        }
        rotate(turn) { drawPath(ringPath, ring) }
        // the pawl: a lever from its pivot to the tooth at the top
        val pivot = center + Offset(cos(-0.49f) * r * 0.30f, sin(-0.49f) * r * 0.30f)
        val tip = center + Offset(cos(-1.675f) * r * 0.71f, sin(-1.675f) * r * 0.71f)
        drawLine(pawl, pivot, tip, strokeWidth = r * 0.12f, cap = StrokeCap.Round)
        drawCircle(pawl, r * 0.125f, pivot)
    }
}

/** A counter that rolls like an odometer, one step up per increase. Fixed-width digits so nothing jumps. */
@Composable
fun Odometer(value: Long, size: TextUnit, color: Color = P.ink, modifier: Modifier = Modifier) {
    if (reducedMotion()) return Num(count(value), size, modifier, color)
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically(tween(160)) { it / 2 } + fadeIn(tween(160)))
                .togetherWith(slideOutVertically(tween(120)) { -it / 2 } + fadeOut(tween(120)))
        },
        label = "odometer",
        modifier = modifier,
    ) { Num(count(it), size, color = color) }
}

/** Buttons give way a little under the finger: 0.97, quick. */
@Composable
fun Modifier.pressable(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(if (pressed) 100 else 160), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** One tick per cog, like the pawls clicking over the ratchet. A held multi-shift ticks in a short run. */
suspend fun View.ticks(n: Int) {
    val kind = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    repeat(n.coerceAtMost(6)) {
        performHapticFeedback(kind)
        delay(45)
    }
}
