package io.github.brickystudios.freehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
fun StatsScreen(totals: Totals, ride: RideNow?, gears: Gears?, km: Double?, bikeName: String?, onReset: () -> Unit) {
    // the ride in progress counts too, so the numbers move while you ride
    val usage = (totals.usage.keys + (ride?.usage?.keys ?: emptySet())).associateWith { (totals.usage[it] ?: 0) + (ride?.usage?.get(it) ?: 0) }
    val shifts = totals.shifts + (ride?.let { it.rear + it.front } ?: 0)
    val ms = usage.values.sum()
    val frontMax = gears?.frontMax ?: usage.keys.maxOfOrNull { it.front } ?: 2
    val rearMax = gears?.rearMax ?: usage.keys.maxOfOrNull { it.rear } ?: 12

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Label(tr("Seit Beginn", "All time") + (bikeName?.let { " · $it" } ?: ""))
        Num(count(shifts), 88.sp)
        BodyText(
            tr("Schaltvorgänge in ${totals.rides.size + (if (ride != null) 1 else 0)} Fahrten · ${duration(ms)}", "shifts in ${totals.rides.size + (if (ride != null) 1 else 0)} rides · ${duration(ms)}"),
            color = P.muted,
        )
        if (km != null) {
            Spacer(Modifier.height(20.dp))
            Field(count(Math.round(km)) + " km", tr("Gesamt · per GPS gemessen", "Total · measured by GPS"), size = 44.sp)
        }
        Spacer(Modifier.height(24.dp))
        if (ms == 0L) {
            Panel { BodyText(tr("Noch keine Fahrt. Sobald das Schaltwerk verbunden ist, zählt Freehub jeden Gangwechsel.", "No ride yet. Once the derailleur is connected, Freehub counts every shift."), color = P.muted) }
            return@Column
        }
        val fav = Usage.favourite(usage)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("%.0f".fmt(shifts / (ms / 3_600_000f).coerceAtLeast(0.01f)), tr("pro Stunde", "per hour"), Modifier.weight(1f))
            val cross = Usage.crossShare(usage, frontMax, rearMax)
            Field("%.0f %%".fmt(cross * 100), tr("Schräglauf", "Cross-chain"), Modifier.weight(1f), color = if (cross > 0.1f) P.warn else P.ink)
            Field(fav?.let { gearName(it, gears) } ?: "–", tr("Lieblingsgang", "Favourite"), Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        Label(tr("Wo deine Zeit liegt", "Where your time goes"))
        Spacer(Modifier.height(10.dp))
        Heatmap(usage, frontMax, rearMax, gears)
        Spacer(Modifier.height(8.dp))
        BodyText(
            tr("Je kräftiger das Rosa, desto länger warst du in dem Gang. Orange umrandet: Schräglauf.", "The deeper the pink, the longer you rode that gear. Orange outline: cross-chained."),
            color = P.faint, size = 14.sp,
        )
        Spacer(Modifier.height(28.dp))
        Label(tr("Letzte Fahrten", "Recent rides"))
        Spacer(Modifier.height(6.dp))
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()) }
        totals.rides.take(30).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BodyText(fmt.format(Date(r.start)), weight = FontWeight.Medium)
                    BodyText(duration(r.ms) + (if (r.meters > 0) " · " + kmText(r.meters) else "") + " · " + "%.0f".fmt(r.shifts / (r.ms / 3_600_000f).coerceAtLeast(0.01f)) + tr(" pro Stunde", " per hour"), color = P.muted, size = 14.sp)
                }
                Num(count(r.shifts.toLong()), 30.sp, weight = FontWeight.Bold)
            }
            Divider()
        }
        Spacer(Modifier.height(32.dp))
        var confirm by remember { mutableStateOf(false) }
        if (!confirm) Action(tr("Zähler zurücksetzen", "Reset counters"), Modifier.fillMaxWidth(), primary = false) { confirm = true }
        else {
            BodyText(tr("Alle Fahrten und Zähler löschen? Das lässt sich nicht rückgängig machen.", "Delete all rides and counters? This cannot be undone."), color = P.warn)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(tr("Löschen", "Delete"), Modifier.weight(1f)) { confirm = false; onReset() }
                Action(tr("Abbrechen", "Cancel"), Modifier.weight(1f), primary = false) { confirm = false }
            }
        }
        TabBarRoom()
    }
}

/** Rings as rows (big ring on top), cogs as columns from easiest to hardest. */
@Composable
private fun Heatmap(usage: Map<Gear, Long>, frontMax: Int, rearMax: Int, gears: Gears?) {
    val shares = Usage.shares(usage)
    val top = shares.values.maxOrNull() ?: 1f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(36.dp))
            for (rear in 1..rearMax) {
                Label(gears?.let { Teeth.at(it.sprockets, rear, rearMax) }?.toString() ?: "$rear", Modifier.weight(1f).padding(bottom = 4.dp), color = P.faint)
            }
        }
        for (front in frontMax downTo 1) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(gears?.let { Teeth.at(it.chainrings, front, frontMax) }?.toString() ?: "$front", Modifier.width(36.dp), color = P.faint)
                for (rear in 1..rearMax) {
                    val g = Gear(front, rear)
                    val s = shares[g] ?: 0f
                    val cross = frontMax >= 2 && ((front == frontMax && rear <= 2) || (front == 1 && rear >= rearMax - 1))
                    Box(
                        Modifier.weight(1f).aspectRatio(0.62f).padding(horizontal = 1.5.dp)
                            .background(if (s > 0f) P.accent.copy(alpha = 0.12f + 0.88f * (s / top)) else P.track, RoundedCornerShape(2.dp))
                            .then(if (cross) Modifier.border(1.dp, P.warn, RoundedCornerShape(2.dp)) else Modifier)
                            .semantics { contentDescription = "${gearName(g, gears)}: ${"%.0f".fmt(s * 100)} %" },
                    )
                }
            }
        }
    }
}

fun gearName(g: Gear, gears: Gears?): String {
    val f = gears?.let { Teeth.at(it.chainrings, g.front, it.frontMax) }
    val r = gears?.let { Teeth.at(it.sprockets, g.rear, it.rearMax) }
    return if (f != null && r != null) "$f×$r" else "${g.front}·${g.rear}"
}
