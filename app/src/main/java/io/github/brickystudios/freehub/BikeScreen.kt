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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tyre sizes and their rolling circumference in mm (700c road and gravel). */
private val WHEELS = listOf("700×25C" to 2105, "700×28C" to 2136, "700×30C" to 2146, "700×32C" to 2155, "700×35C" to 2168, "700×40C" to 2200)

@Composable
fun BikeScreen(
    bike: Bike, gears: Gears?, odo: Odo?, power: List<Charge>,
    autoStart: Boolean?, onAutoStart: (Boolean) -> Unit,
    onDemo: (Boolean) -> Unit, onCheckUpdate: () -> Unit, updateNote: String?,
) {
    val ctx = LocalContext.current
    val when_ = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Label(tr("Schaltwerk", "Derailleur"))
        Num(model(bike.name) ?: tr("Nicht verbunden", "Not connected"), 52.sp)
        Spacer(Modifier.height(12.dp))
        val last = power.lastOrNull()
        val level = bike.battery ?: last?.percent
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(level?.let { "$it %" } ?: "–", tr("Akku", "Battery"), Modifier.weight(1f), color = if ((level ?: 100) <= 20) P.warn else P.ink)
            Field(level?.let { approxKm(Range.remainingKm(it, power)) } ?: "–", tr("Reicht noch", "Range left"), Modifier.weight(1f))
            Field(bike.firmware ?: "–", "Firmware", Modifier.weight(1f))
        }

        Spacer(Modifier.height(28.dp))
        Label(tr("Akku", "Battery"))
        Spacer(Modifier.height(4.dp))
        if (bike.battery == null && last != null) Info(tr("Letzter Stand", "Last reading"), "${last.percent} % · " + when_.format(Date(last.at)))
        val learned = Range.learned(power)
        Info(
            tr("Gerechnet mit", "Based on"),
            learned?.let { "%.0f km ".fmt(it) + tr("pro Prozent · deine Fahrten", "per percent · your rides") }
                ?: tr("Shimano: ≈ 1.000 km pro Ladung", "Shimano: ≈ 1,000 km per charge"),
        )
        Info(tr("Zuletzt geladen", "Last charged"), Range.lastCharged(power)?.let { when_.format(Date(it)) } ?: "–")
        if (learned == null) BodyText(
            if (odo == null) tr("Mit Strava verbunden lernt Freehub, wie weit dein Akku wirklich reicht.", "Connected to Strava, Freehub learns how far your battery really goes.")
            else tr("Nach ein paar Fahrten rechnet Freehub mit deinem echten Verbrauch.", "After a few rides Freehub uses your real consumption."),
            Modifier.padding(top = 8.dp), color = P.faint, size = 14.sp,
        )

        Spacer(Modifier.height(28.dp))
        Label(tr("Antrieb", "Drivetrain"))
        Spacer(Modifier.height(4.dp))
        Info(tr("Seriennummer", "Serial number"), bike.serial ?: "–")
        Drivetrain(bike, gears)
        Info(tr("Schaltmodus", "Shift mode"), gears?.let { modeName(it.mode) } ?: "–")
        Info(tr("Multi-Shift-Intervall", "Multi-shift interval"), gears?.multiShiftInterval?.toString() ?: "–")
        Info(
            tr("Feinjustierung hinten", "Rear fine-tune"),
            gears?.rearAdjust?.let { a -> (if (a == 0) "0" else "%+d".fmt(a)) + (if (gears.rearAdjustMin != null && gears.rearAdjustMax != null) "  (${gears.rearAdjustMin} … +${gears.rearAdjustMax})" else "") } ?: "–",
        )
        val raw = remember(bike.link) { Prefs.rawGears(ctx) }
        if (raw != null && !bike.demo) {
            val clip = LocalClipboardManager.current
            SettingRow(tr("Rohdaten vom Schaltwerk", "Raw data from the derailleur"), raw, onClick = { clip.setText(AnnotatedString(raw)) }) {
                BodyText(tr("Kopieren", "Copy"), color = P.accent, weight = FontWeight.SemiBold)
            }
            Divider()
        }

        Spacer(Modifier.height(28.dp))
        Label(tr("Kilometer", "Distance"))
        Spacer(Modifier.height(4.dp))
        Kilometres(bike.demo, odo, when_)

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

        if (autoStart != null) {
            Spacer(Modifier.height(28.dp))
            Label(tr("Automatisch", "Automatic"))
            SettingRow(
                tr("Starten, wenn das Schaltwerk aufwacht", "Start when the derailleur wakes up"),
                tr(
                    "zählt auch mit geschlossener App. Zum Einschalten einmal schalten, dann das Schaltwerk in der Liste antippen.",
                    "counts with the app closed too. To switch on, shift once, then tap the derailleur in the list.",
                ),
                onClick = { onAutoStart(!autoStart) },
            ) { Toggle(autoStart) }
            Divider()
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
            updateNote ?: tr("fragt beim Start GitHub nach einer neuen Version", "asks GitHub for a new version at start"),
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

/** Crank and cassette: as reported, or picked by hand when the derailleur reports none we know (or a wrong one). */
@Composable
private fun Drivetrain(bike: Bike, gears: Gears?) {
    val ctx = LocalContext.current
    if (bike.demo) {
        Info(tr("Kurbel", "Crankset"), gears?.chainrings?.joinToString("/") ?: "–")
        Info(tr("Kassette", "Cassette"), gears?.sprockets?.let(::cassette) ?: "–")
        return
    }
    val reported = remember(bike.link) { Prefs.reportedGears(ctx) }
    var rings by remember { mutableIntStateOf(Prefs.rings(ctx)) }
    var cogs by remember { mutableIntStateOf(Prefs.cogs(ctx)) }
    fun note(picked: Boolean) = if (picked) tr("von Hand gewählt · tippen zum Wechseln", "picked by hand · tap to change")
    else tr("vom Schaltwerk · tippen, falls falsch", "from the derailleur · tap if wrong")
    // -1 first: back to what the derailleur says
    val ringChoices = listOf(-1) + Teeth.ringPatterns(reported?.frontMax ?: 2)
    val cogChoices = listOf(-1) + Teeth.cogPatterns(reported?.rearMax ?: 12)
    SettingRow(tr("Kurbel", "Crankset"), note(rings >= 0), onClick = {
        rings = ringChoices[(ringChoices.indexOf(rings) + 1) % ringChoices.size]
        Prefs.setRings(ctx, rings)
        Di2.repick()
    }) { BodyText((Teeth.chainrings(rings) ?: reported?.chainrings)?.joinToString("/") ?: tr("unbekannt", "unknown"), weight = FontWeight.SemiBold) }
    Divider()
    SettingRow(tr("Kassette", "Cassette"), note(cogs >= 0), onClick = {
        cogs = cogChoices[(cogChoices.indexOf(cogs) + 1) % cogChoices.size]
        Prefs.setCogs(ctx, cogs)
        Di2.repick()
    }) { BodyText((Teeth.sprockets(cogs) ?: reported?.sprockets)?.let(::cassette) ?: tr("unbekannt", "unknown"), weight = FontWeight.SemiBold) }
    Divider()
}

private fun cassette(t: List<Int>) = "${t.first()}–${t.last()} · ${t.size}-" + tr("fach", "speed")

/** The derailleur measures no distance: the bike's odometer comes from Strava. */
@Composable
private fun Kilometres(demo: Boolean, odo: Odo?, when_: DateFormat) {
    val ctx = LocalContext.current
    val error by Strava.error.collectAsStateWithLifecycle()
    val bikes by Strava.bikes.collectAsStateWithLifecycle()
    if (demo || odo != null || Strava.connected(ctx)) {
        if (odo != null) {
            SettingRow(
                tr("Rad bei Strava", "Bike on Strava"),
                if (bikes.size > 1) tr("tippen zum Wechseln", "tap to change") else tr("Stand ", "As of ") + when_.format(Date(odo.at)),
                onClick = if (bikes.size > 1 && !demo) ({
                    val i = bikes.indexOfFirst { it.name == odo.bike }
                    Strava.choose(ctx, bikes[(i + 1) % bikes.size])
                }) else null,
            ) { BodyText(odo.bike, weight = FontWeight.SemiBold) }
            Divider()
            Info(tr("Gesamt", "Total"), count(Math.round(odo.km)) + " km")
        }
        if (!demo) Action(tr("Strava trennen", "Disconnect Strava"), Modifier.padding(top = 12.dp), primary = false) { Strava.disconnect(ctx) }
    } else {
        BodyText(
            tr(
                "Das Schaltwerk misst keine Strecke. Freehub holt den km-Stand deines Rads von Strava, auch von Fahrten ohne Freehub. Dafür brauchst du eine eigene Strava-API-App: unter strava.com/settings/api anlegen, als Callback-Domain „localhost“ eintragen.",
                "The derailleur measures no distance. Freehub takes your bike's odometer from Strava, rides without Freehub included. You need your own Strava API app for that: create it at strava.com/settings/api with \"localhost\" as callback domain.",
            ),
            color = P.muted, size = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        var id by remember { mutableStateOf(Strava.clientId(ctx)) }
        var secret by remember { mutableStateOf("") }
        Input(id, { id = it.filter(Char::isDigit) }, "Client ID", digits = true)
        Input(secret, { secret = it.trim() }, "Client Secret", secret = true)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action(tr("Verbinden", "Connect"), Modifier.weight(1f), primary = id.isNotBlank() && secret.isNotBlank()) {
                if (id.isNotBlank() && secret.isNotBlank()) Strava.login(ctx, id, secret)
            }
            Action(tr("API-Seite", "API page"), Modifier.weight(1f), primary = false) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StravaApi.SETTINGS)))
            }
        }
    }
    error?.let { BodyText(it, Modifier.padding(top = 10.dp), color = P.warn, size = 15.sp) }
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
