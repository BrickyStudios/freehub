package io.github.brickystudios.freehub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** "RDR7150 123" → "123": the number that tells two derailleurs of the same model apart */
fun unitNumber(name: String?) = name?.substringAfter(' ', "")?.trim()?.takeIf { it.isNotEmpty() }

/** rough distance from the signal strength, so the nearest derailleur is easy to spot */
fun closeness(rssi: Int) = when {
    rssi >= -60 -> tr("ganz nah", "very close")
    rssi >= -75 -> tr("in der Nähe", "nearby")
    else -> tr("weiter weg", "further away")
}

/**
 * Every derailleur in range, nearest first, none of them connected. The rider taps theirs; from then on
 * Freehub connects to that one only, so a second bike of the same model next to it is never touched.
 */
@Composable
fun PairList(onPaired: (Profile) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        Di2.discover(ctx, true)
        onDispose { Di2.discover(ctx, false) }
    }
    val nearby by Di2.nearby.collectAsStateWithLifecycle()
    val bikes by Garage.bikes.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    // quiet for 20 s: asleep again or out of range
    val seen = nearby.values.filter { now - it.at < 20_000 }.sortedByDescending { it.rssi }

    Column {
        if (seen.isEmpty()) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Ratchet(Modifier.size(36.dp), coasting = true)
                Spacer(Modifier.width(16.dp))
                BodyText(tr("Suche … Schalt einmal an deinem Rad, dann taucht sein Schaltwerk hier auf.", "Searching … Shift once on your bike, then its derailleur shows up here."), color = P.muted, size = 15.sp)
            }
        }
        seen.forEach { n ->
            val paired = bikes.any { it.address == n.address }
            SettingRow(
                (model(n.name) ?: "Di2") + (unitNumber(n.name)?.let { " · $it" } ?: ""),
                closeness(n.rssi) + if (paired) " · " + tr("schon gekoppelt", "already paired") else "",
                onClick = if (paired) null else ({ onPaired(Garage.add(n.address, n.name)) }),
            ) { if (!paired) BodyText(tr("Koppeln", "Pair"), color = P.accent, weight = FontWeight.Medium) }
            Divider()
        }
        if (seen.size > 1) BodyText(
            tr(
                "Mehrere Schaltwerke in der Nähe. Das oberste ist am nächsten. Schalt an deinem Rad: sein Schaltwerk wacht auf und rückt nach oben.",
                "Several derailleurs in range. The top one is the nearest. Shift on your bike: its derailleur wakes up and moves to the top.",
            ),
            Modifier.padding(top = 12.dp), color = P.faint, size = 14.sp,
        )
    }
}
