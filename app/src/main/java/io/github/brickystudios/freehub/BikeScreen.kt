package io.github.brickystudios.freehub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tyre sizes and their rolling circumference in mm (700c road and gravel). */
private val WHEELS = listOf("700×25C" to 2105, "700×28C" to 2136, "700×30C" to 2146, "700×32C" to 2155, "700×35C" to 2168, "700×40C" to 2200)

@Composable
fun BikeScreen(bike: Bike, gears: Gears?, onDemo: (Boolean) -> Unit, onCheckUpdate: () -> Unit, updateNote: String?) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Label(tr("Schaltwerk", "Derailleur"))
        Num(model(bike.name) ?: tr("Nicht verbunden", "Not connected"), 52.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(bike.battery?.let { "$it %" } ?: "–", tr("Akku", "Battery"), Modifier.weight(1f), color = if ((bike.battery ?: 100) <= 20) P.warn else P.ink)
            Field(bike.firmware ?: "–", "Firmware", Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(28.dp))
        Label(tr("Antrieb", "Drivetrain"))
        Spacer(Modifier.height(4.dp))
        Info(tr("Seriennummer", "Serial number"), bike.serial ?: "–")
        Info(tr("Kurbel", "Crankset"), gears?.chainrings?.joinToString("/") ?: "–")
        Info(tr("Kassette", "Cassette"), gears?.sprockets?.let { "${it.first()}–${it.last()} · ${it.size}-" + tr("fach", "speed") } ?: "–")
        Info(tr("Schaltmodus", "Shift mode"), gears?.let { modeName(it.mode) } ?: "–")
        Info(tr("Multi-Shift-Intervall", "Multi-shift interval"), gears?.multiShiftInterval?.toString() ?: "–")
        Info(
            tr("Feinjustierung hinten", "Rear fine-tune"),
            gears?.rearAdjust?.let { a -> (if (a == 0) "0" else "%+d".fmt(a)) + (if (gears.rearAdjustMin != null && gears.rearAdjustMax != null) "  (${gears.rearAdjustMin} … +${gears.rearAdjustMax})" else "") } ?: "–",
        )

        Spacer(Modifier.height(28.dp))
        Label(tr("Tastentest", "Button test"))
        Spacer(Modifier.height(8.dp))
        Panel {
            val b = bike.buttons
            BodyText(
                if (b == null) tr("Drück eine Zusatztaste oben am Griff. Hier steht dann, welcher D-Fly-Kanal ankommt.", "Press an extra button on top of the hood. The D-Fly channel shows up here.")
                else b.channels.entries.joinToString("\n") { (ch, p) -> "D-FLY $ch · ${pressName(p)}" },
                color = if (b == null) P.muted else P.ink, weight = if (b == null) FontWeight.Normal else FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(28.dp))
        Label(tr("Einstellungen", "Settings"))
        var wheel by remember { mutableIntStateOf(Prefs.wheel(ctx)) }
        SettingRow(
            tr("Reifen", "Tyre"),
            tr("für die Entfaltung · tippen zum Wechseln", "for development · tap to change"),
            onClick = {
                val i = WHEELS.indexOfFirst { it.second == wheel }
                wheel = WHEELS[(i + 1) % WHEELS.size].second
                Prefs.setWheel(ctx, wheel)
            },
        ) { BodyText(WHEELS.firstOrNull { it.second == wheel }?.first ?: "$wheel mm", weight = FontWeight.SemiBold) }
        Divider()
        var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
        SettingRow(tr("Sprache", "Language"), tr("tippen zum Wechseln", "tap to change"), onClick = {
            lang = when (lang) { null -> "de"; "de" -> "en"; else -> null }
            Prefs.setLang(ctx, lang)
            Lang.chosen = lang
            (ctx as? Activity)?.recreate()
        }) {
            BodyText(when (lang) { "de" -> "Deutsch"; "en" -> "English"; else -> tr("wie das Handy", "phone default") }, weight = FontWeight.SemiBold)
        }
        Divider()
        var haptics by remember { mutableStateOf(Prefs.haptics(ctx)) }
        SettingRow(tr("Klick bei jedem Gang", "Tick on every shift"), tr("kurze Vibration pro Ritzel, solange die App offen ist", "a short buzz per cog while the app is open"), onClick = {
            haptics = !haptics
            Prefs.setHaptics(ctx, haptics)
        }) { Toggle(haptics) }
        Divider()
        var background by remember { mutableStateOf(Prefs.background(ctx)) }
        SettingRow(
            tr("Mit Bildschirm aus weiterzählen", "Keep counting with the screen off"),
            tr("hält die Verbindung während der Fahrt, Meldung in der Leiste", "keeps the link during the ride, with a notification"),
            onClick = { background = !background; Prefs.setBackground(ctx, background) },
        ) { Toggle(background) }
        Divider()
        var updates by remember { mutableStateOf(Prefs.updates(ctx)) }
        SettingRow(
            tr("Nach Updates suchen", "Check for updates"),
            updateNote ?: tr("fragt beim Start GitHub, sonst geht nichts ins Netz", "asks GitHub at start, nothing else goes online"),
            onClick = { updates = !updates; Prefs.setUpdates(ctx, updates) },
        ) { Toggle(updates) }
        if (updates) Action(tr("Jetzt prüfen", "Check now"), Modifier.padding(bottom = 8.dp), primary = false, onClick = onCheckUpdate)
        Divider()
        SettingRow(tr("Demo-Modus", "Demo mode"), tr("erfundene Fahrt, echte Zahlen bleiben unberührt", "made-up ride, your real numbers stay untouched"), onClick = { onDemo(!bike.demo) }) { Toggle(bike.demo) }
        Divider()
        SettingRow(tr("Quellcode und Protokoll", "Source code and protocol"), "github.com/BrickyStudios/freehub", onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/freehub")))
        })
        Spacer(Modifier.height(20.dp))
        BodyText(
            "Freehub ${BuildConfig.VERSION_NAME} · GPL-3.0 · " + tr("Nicht von Shimano. Shimano und Di2 sind Marken der SHIMANO INC.", "Not made by Shimano. Shimano and Di2 are trademarks of SHIMANO INC."),
            color = P.faint, size = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BodyText(label, Modifier.weight(1f), color = P.muted)
        BodyText(value, weight = FontWeight.SemiBold)
    }
    Divider()
}

/** "RDR7150 123" → "RD-R7150" */
fun model(name: String?): String? {
    val m = Regex("^RD([A-Z])(\\d{4})").find(name ?: return null) ?: return name.substringBefore(' ')
    return "RD-${m.groupValues[1]}${m.groupValues[2]}"
}
