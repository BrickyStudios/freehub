package io.github.brickystudios.freehub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(bike: Bike, ride: RideNow?, wheelMm: Int, needsPermission: Boolean, onAllow: () -> Unit, onBluetooth: () -> Unit, onDemo: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        StatusLine(bike)
        Spacer(Modifier.height(20.dp))
        val g = bike.gears
        when {
            needsPermission -> Welcome(onAllow, onDemo)
            bike.link == Link.NO_BLUETOOTH -> Hint(tr("Bluetooth ist aus", "Bluetooth is off"), tr("Freehub spricht per Bluetooth mit dem Schaltwerk.", "Freehub talks to the derailleur over Bluetooth."), tr("Einschalten", "Turn on"), onBluetooth, onDemo)
            g == null && bike.link != Link.CONNECTED -> Searching(onDemo)
            g == null -> Hint(tr("Verbunden", "Connected"), tr("Schalt einmal, dann schickt das Schaltwerk den Gang.", "Shift once and the derailleur sends the gear."), null, {}, null)
            else -> Cockpit(g, ride, wheelMm, bike.buttons)
        }
    }
}

@Composable
private fun StatusLine(bike: Bike) {
    val (color, text) = when (bike.link) {
        Link.CONNECTED -> P.accent to (bike.name ?: "Di2") + if (bike.demo) " · Demo" else ""
        Link.CONNECTING -> P.muted to tr("Verbinde …", "Connecting …")
        Link.SEARCHING -> P.faint to tr("Suche Schaltwerk", "Looking for derailleur")
        Link.NO_BLUETOOTH -> P.warn to tr("Bluetooth aus", "Bluetooth off")
        Link.OFF -> P.faint to tr("Nicht verbunden", "Not connected")
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(10.dp))
        Label(text, Modifier.weight(1f), color = P.ink)
        bike.battery?.let { Battery(it) }
    }
}

@Composable
fun Battery(percent: Int) {
    val low = percent <= 20
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Akku $percent %" }) {
        Box(Modifier.size(width = 26.dp, height = 13.dp).background(P.track, RoundedCornerShape(2.dp)).padding(2.dp)) {
            Box(Modifier.fillMaxWidth(percent / 100f).height(9.dp).background(if (low) P.warn else P.ink, RoundedCornerShape(1.dp)))
        }
        Box(Modifier.size(width = 2.dp, height = 5.dp).background(P.track)) // the terminal nub
        Spacer(Modifier.width(8.dp))
        Num("$percent %", 18.sp, weight = FontWeight.Bold, color = if (low) P.warn else P.ink)
    }
}

@Composable
private fun Cockpit(g: Gears, ride: RideNow?, wheelMm: Int, buttons: Instant.Buttons?) {
    val front = g.frontTeeth
    val rear = g.rearTeeth
    // rolls up when the gear gets harder, down when it gets easier: the eye confirms the shift without reading
    AnimatedContent(
        targetState = Triple(front, rear, g.rear),
        transitionSpec = {
            val up = (targetState.third ?: 0) >= (initialState.third ?: 0)
            (slideInVertically(tween(180)) { if (up) it / 3 else -it / 3 } + fadeIn(tween(180)))
                .togetherWith(slideOutVertically(tween(140)) { if (up) -it / 3 else it / 3 } + fadeOut(tween(140)))
        },
        label = "gear",
    ) { (f, r, _) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Num(f?.toString() ?: "–", 120.sp)
            BodyText("×", Modifier.padding(horizontal = 10.dp), color = P.faint, size = 48.sp)
            Num(r?.toString() ?: "–", 120.sp, color = P.accent)
        }
    }
    Label(
        tr("Vorne ${g.front ?: "–"}/${g.frontMax ?: "–"} · Hinten ${g.rear ?: "–"}/${g.rearMax ?: "–"}", "Front ${g.front ?: "–"}/${g.frontMax ?: "–"} · Rear ${g.rear ?: "–"}/${g.rearMax ?: "–"}"),
    )
    Spacer(Modifier.height(24.dp))
    Drivetrain(g)
    Spacer(Modifier.height(24.dp))

    val ratio = if (front != null && rear != null) front.toFloat() / rear else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(ratio?.let { "%.2f".fmt(it) } ?: "–", tr("Übersetzung", "Ratio"), Modifier.weight(1f))
        Field(ratio?.let { "%.2f m".fmt(it * wheelMm / 1000f) } ?: "–", tr("Entfaltung", "Development"), Modifier.weight(1f))
        Field(modeShort(g.mode), tr("Schaltmodus", "Shift mode"), Modifier.weight(1f))
    }
    Spacer(Modifier.height(20.dp))
    Panel {
        Column {
            Label(tr("Diese Fahrt", "This ride"))
            Spacer(Modifier.height(6.dp))
            val n = (ride?.rear ?: 0).toLong() + (ride?.front ?: 0)
            Row(verticalAlignment = Alignment.Bottom) {
                Num(count(n), 56.sp)
                Spacer(Modifier.width(10.dp))
                BodyText(shiftsWord(n), color = P.muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(count((ride?.rear ?: 0).toLong()), tr("Hinten", "Rear"), Modifier.weight(1f), 26.sp)
                Field(count((ride?.front ?: 0).toLong()), tr("Vorne", "Front"), Modifier.weight(1f), 26.sp)
                Field(duration(ride?.ms ?: 0), tr("Dauer", "Time"), Modifier.weight(1f), 26.sp)
            }
        }
    }
    DFlyFlash(buttons)
}

/** Chainrings left, cassette right; bar height follows the teeth, the gear in use is pink. */
@Composable
private fun Drivetrain(g: Gears) {
    val rings = (g.chainrings ?: List(g.frontMax ?: 1) { 0 }).reversed() // small ring first, like positions
    val cogs = (g.sprockets ?: List(g.rearMax ?: 12) { 0 }).reversed() // largest cog = position 1 first
    val maxTeeth = (rings + cogs).maxOrNull()?.takeIf { it > 0 } ?: 1
    val off = P.track
    val on = P.accent
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).semantics {
            contentDescription = "Gang ${g.front}/${g.frontMax}, ${g.rear}/${g.rearMax}"
        },
    ) {
        val bars = rings.size + cogs.size
        val gap = 6.dp.toPx()
        val split = 22.dp.toPx() // air between crank and cassette
        val w = (size.width - split - gap * (bars - 2)) / bars
        var x = 0f
        fun bar(teeth: Int, used: Boolean) {
            // unknown teeth (pattern not in the table): equal bars, still shows the position
            val h = if (teeth > 0) size.height * (0.28f + 0.72f * teeth / maxTeeth) else size.height * 0.5f
            drawRoundRect(if (used) on else off, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(2.dp.toPx()))
            x += w + gap
        }
        rings.forEachIndexed { i, t -> bar(t, g.front == i + 1) }
        x += split - gap
        cogs.forEachIndexed { i, t -> bar(t, g.rear == i + 1) }
    }
}

@Composable
private fun DFlyFlash(b: Instant.Buttons?) {
    var shown by remember { mutableStateOf<Instant.Buttons?>(null) }
    LaunchedEffect(b?.seq) {
        if (b == null) return@LaunchedEffect
        shown = b
        delay(2_500)
        shown = null
    }
    val s = shown ?: return
    Spacer(Modifier.height(12.dp))
    Panel(pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(P.accent)
            Spacer(Modifier.width(10.dp))
            BodyText(s.channels.entries.joinToString { (ch, p) -> "D-FLY $ch · ${pressName(p)}" }, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Welcome(onAllow: () -> Unit, onDemo: () -> Unit) {
    Num(tr("Dein Di2,\nauf einen Blick.", "Your Di2,\nat a glance."), 52.sp)
    Spacer(Modifier.height(16.dp))
    BodyText(
        tr(
            "Gang, Akku, Schaltvorgänge und welche Gänge du wirklich fährst. Direkt vom Schaltwerk, ohne Konto und ohne Cloud.",
            "Gear, battery, shift count and which gears you really ride. Straight from the derailleur, no account, no cloud.",
        ),
        color = P.muted, size = 18.sp,
    )
    Spacer(Modifier.height(28.dp))
    Action(tr("Bluetooth erlauben", "Allow Bluetooth"), Modifier.fillMaxWidth(), onClick = onAllow)
    Spacer(Modifier.height(10.dp))
    Action(tr("Erst die Demo ansehen", "See the demo first"), Modifier.fillMaxWidth(), primary = false, onClick = onDemo)
    Spacer(Modifier.height(18.dp))
    BodyText(tr("Läuft mit 12-fach Di2 von Shimano (105, Ultegra, Dura-Ace, GRX).", "Works with Shimano 12-speed Di2 (105, Ultegra, Dura-Ace, GRX)."), color = P.faint, size = 14.sp)
}

@Composable
private fun Searching(onDemo: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.25f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        val accent = P.accent
        Canvas(Modifier.size(140.dp).alpha(a)) {
            drawCircle(accent, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
            drawCircle(accent, radius = size.minDimension / 4, style = Stroke(1.dp.toPx()))
        }
    }
    Num(tr("Einmal schalten.", "Shift once."), 44.sp)
    Spacer(Modifier.height(10.dp))
    BodyText(
        tr("Das Schaltwerk schläft, wenn das Rad steht. Ein Klick am Hebel weckt es, dann verbindet sich Freehub von selbst.", "The derailleur sleeps while the bike stands still. One click on a lever wakes it, then Freehub connects on its own."),
        color = P.muted, size = 17.sp,
    )
    Spacer(Modifier.height(24.dp))
    Action(tr("Demo ansehen", "See the demo"), Modifier.fillMaxWidth(), primary = false, onClick = onDemo)
}

@Composable
private fun Hint(title: String, text: String, action: String?, onAction: () -> Unit, onDemo: (() -> Unit)?) {
    Num(title, 44.sp)
    Spacer(Modifier.height(10.dp))
    BodyText(text, color = P.muted, size = 17.sp)
    Spacer(Modifier.height(24.dp))
    if (action != null) Action(action, Modifier.fillMaxWidth(), onClick = onAction)
    if (onDemo != null) {
        Spacer(Modifier.height(10.dp))
        Action(tr("Demo ansehen", "See the demo"), Modifier.fillMaxWidth(), primary = false, onClick = onDemo)
    }
}

fun modeName(m: ShiftMode) = when (m) {
    ShiftMode.MANUAL -> tr("Manuell", "Manual")
    ShiftMode.SYNCHRO_S1 -> "Synchro S1"
    ShiftMode.SYNCHRO_S2 -> "Synchro S2"
    ShiftMode.ADJUST -> tr("Justieren", "Adjust")
    ShiftMode.AUTO -> "Auto"
    ShiftMode.AUTO2 -> "Auto 2"
    ShiftMode.PROTECTION_RESET -> tr("Schutz-Reset", "Protection reset")
    ShiftMode.UNKNOWN -> "–"
}

/** fits a third of the screen next to the ratio */
fun modeShort(m: ShiftMode) = when (m) {
    ShiftMode.MANUAL -> tr("Manuell", "Manual")
    ShiftMode.SYNCHRO_S1 -> "S1"
    ShiftMode.SYNCHRO_S2 -> "S2"
    else -> modeName(m)
}

fun shiftsWord(n: Long) = if (n == 1L) tr("Schaltvorgang", "shift") else tr("Schaltvorgänge", "shifts")

fun pressName(p: Press) = when (p) {
    Press.SHORT -> tr("kurz", "short")
    Press.LONG -> tr("lang", "long")
    Press.DOUBLE -> tr("doppelt", "double")
}
