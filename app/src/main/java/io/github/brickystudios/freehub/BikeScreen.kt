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
    bike: Bike, gears: Gears?, km: Double?, power: List<Charge>, loc: Loc, onLocation: () -> Unit,
    autoStart: Boolean?, onAutoStart: (Boolean) -> Unit,
    onDemo: (Boolean) -> Unit, onCheckUpdate: () -> Unit, updateNote: String?, onSetup: () -> Unit,
    profile: Profile?, bikes: List<Profile>, onPaired: (Profile) -> Unit, onRemove: (Profile) -> Unit,
) {
    val ctx = LocalContext.current
    val when_ = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Label(model(bike.name ?: profile?.model)?.let { it + (unitNumber(bike.name ?: profile?.model)?.let { n -> " · $n" } ?: "") } ?: tr("Rad", "Bike"))
        Num(if (bike.demo) "Demo" else profile?.name ?: tr("Kein Rad gekoppelt", "No bike paired"), 52.sp)
        BodyText(
            when {
                bike.demo -> tr("erfundene Fahrt", "made-up ride")
                bike.link == Link.CONNECTED -> tr("verbunden", "connected")
                else -> tr("nicht verbunden", "not connected")
            },
            color = if (bike.link == Link.CONNECTED) P.accent else P.muted, size = 15.sp,
        )
        Spacer(Modifier.height(16.dp))
        val last = power.lastOrNull()
        val level = bike.battery ?: last?.percent
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(level?.let { "$it %" } ?: "–", tr("Akku", "Battery"), Modifier.weight(1f), color = if ((level ?: 100) <= 20) P.warn else P.ink)
            Field(level?.let { approxKm(Range.remainingKm(it, power)) } ?: "–", tr("Reicht noch", "Range left"), Modifier.weight(1f))
            Field(bike.firmware ?: "–", "Firmware", Modifier.weight(1f))
        }

        if (!bike.demo) GarageSection(bike, profile, bikes, onPaired, onRemove)

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
            if (loc == Loc.NONE) tr("Mit GPS-Kilometern lernt Freehub, wie weit dein Akku wirklich reicht.", "With GPS distance, Freehub learns how far your battery really goes.")
            else tr("Nach ein paar Fahrten rechnet Freehub mit deinem echten Verbrauch.", "After a few rides Freehub uses your real consumption."),
            Modifier.padding(top = 8.dp), color = P.faint, size = 14.sp,
        )

        Spacer(Modifier.height(28.dp))
        Label(tr("Antrieb", "Drivetrain"))
        Spacer(Modifier.height(4.dp))
        Info(tr("Seriennummer", "Serial number"), bike.serial ?: "–")
        if (profile != null || bike.demo) DrivetrainPicker(bike, gears) // per bike: nothing to save it to before pairing
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
        Kilometres(km, loc, onLocation)

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
        TyreRow()
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
        var dev by remember { mutableStateOf(Prefs.dev(ctx)) }
        SettingRow(
            tr("Entwickleroptionen", "Developer options"),
            tr("Mitschnitt der Bluetooth-Pakete und Schaltwerk-Speicher, für Fehlerberichte", "capture of the Bluetooth packets and the derailleur memory, for bug reports"),
            onClick = { dev = !dev; Prefs.setDev(ctx, dev); DevLog.on = dev },
        ) { Toggle(dev) }
        if (dev) Developer(bike)
        Divider()
        SettingRow(tr("Einrichtung noch einmal", "Run the setup again"), tr("Bluetooth, Antrieb, Auto-Start, Strava", "Bluetooth, drivetrain, auto start, Strava"), onClick = onSetup)
        Divider()
        SettingRow(tr("Quellcode und Protokoll", "Source code and protocol"), "github.com/BrickyStudios/freehub", onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/freehub")))
        })
        Spacer(Modifier.height(20.dp))
        BodyText(
            "Freehub ${BuildConfig.VERSION_NAME} · GPL-3.0 · " + tr("Nicht von Shimano. Shimano und Di2 sind Marken der SHIMANO INC.", "Not made by Shimano. Shimano and Di2 are trademarks of SHIMANO INC."),
            color = P.faint, size = 13.sp,
        )
        TabBarRoom()
    }
}

/** Crank and cassette: as reported, or picked by hand when the derailleur reports none we know (or a wrong one). */
@Composable
internal fun DrivetrainPicker(bike: Bike, gears: Gears?) {
    val ctx = LocalContext.current
    if (bike.demo) {
        Info(tr("Kurbel", "Crankset"), gears?.chainrings?.joinToString("/") ?: "–")
        Info(tr("Kassette", "Cassette"), gears?.sprockets?.let(::cassette) ?: "–")
        return
    }
    val reported = remember(bike.link) { Prefs.reportedGears(ctx) }
    val active by Garage.active.collectAsStateWithLifecycle()
    var rings by remember(active) { mutableIntStateOf(Prefs.rings(ctx)) }
    var cogs by remember(active) { mutableIntStateOf(Prefs.cogs(ctx)) }
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

/**
 * The paired bikes: switch, rename, remove, pair another. Freehub connects to these only;
 * when one of them wakes up, the screens switch to it on their own.
 */
@Composable
private fun GarageSection(bike: Bike, profile: Profile?, bikes: List<Profile>, onPaired: (Profile) -> Unit, onRemove: (Profile) -> Unit) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(bikes.isEmpty()) }
    var confirm by remember(profile?.address) { mutableStateOf(false) }
    Spacer(Modifier.height(24.dp))
    Label(tr("Räder", "Bikes"))
    Spacer(Modifier.height(4.dp))
    bikes.forEach { b ->
        val on = b.address == profile?.address
        SettingRow(
            b.name,
            (model(b.model) ?: "Di2") + (unitNumber(b.model)?.let { " · $it" } ?: ""),
            onClick = { Di2.switchTo(ctx, b.address) },
        ) { BikeState(b, on, bike) }
        Divider()
    }
    if (profile != null) {
        var name by remember(profile.address) { mutableStateOf(profile.name) }
        Input(name, { name = it.take(30); if (name.isNotBlank()) Garage.update { p -> p.copy(name = name.trim()) } }, tr("Name", "Name"))
        if (!confirm) Action(tr("Dieses Rad entfernen", "Remove this bike"), Modifier.padding(top = 8.dp), primary = false) { confirm = true }
        else {
            BodyText(tr("${profile.name} und alle seine Fahrten löschen?", "Delete ${profile.name} and all its rides?"), Modifier.padding(top = 8.dp), color = P.warn)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(tr("Löschen", "Delete"), Modifier.weight(1f)) { confirm = false; onRemove(profile) }
                Action(tr("Abbrechen", "Cancel"), Modifier.weight(1f), primary = false) { confirm = false }
            }
        }
    }
    if (bikes.size > 1) BodyText(
        tr(
            "Tippe ein Rad an, um dich mit ihm zu verbinden. Stehen mehrere nebeneinander, bleibt Freehub beim gewählten; ein anderes nimmt es nur, wenn das gewählte schläft.",
            "Tap a bike to connect to it. With several side by side, Freehub stays with the chosen one and only takes another when the chosen one is asleep.",
        ),
        Modifier.padding(top = 8.dp), color = P.faint, size = 14.sp,
    )
    Spacer(Modifier.height(12.dp))
    if (!adding) Action(tr("Weiteres Rad koppeln", "Pair another bike"), Modifier.fillMaxWidth(), primary = false) { adding = true }
    else Panel { PairList { adding = false; onPaired(it) } }
}

/** "verbunden" for the bike on the link, "wird gesucht" for the chosen one still asleep, nothing for the rest. */
@Composable
internal fun BikeState(b: Profile, chosen: Boolean, bike: Bike) {
    val (text, color) = when {
        b.address == bike.address && bike.link == Link.CONNECTED -> tr("verbunden", "connected") to P.accent
        chosen && bike.link == Link.CONNECTING -> tr("verbinde …", "connecting …") to P.muted
        chosen -> tr("gewählt · wird gesucht", "chosen · looking") to P.muted
        else -> return
    }
    BodyText(text, color = color, weight = FontWeight.SemiBold, size = 14.sp)
}

/** Developer options: share the capture, read every value, try the derailleur's own memory. */
@Composable
private fun Developer(bike: Bike) {
    val ctx = LocalContext.current
    val lines by DevLog.size.collectAsStateWithLifecycle()
    val memory by Di2.memory.collectAsStateWithLifecycle()
    val connected = bike.link == Link.CONNECTED && !bike.demo
    Panel(Modifier.padding(bottom = 12.dp)) {
        Column {
            Label(tr("Mitschnitt", "Capture"))
            Spacer(Modifier.height(6.dp))
            BodyText(
                tr("$lines Zeilen. Enthält die Seriennummer deines Schaltwerks: nur privat verschicken.", "$lines lines. Contains your derailleur's serial number: only send it privately."),
                color = P.muted, size = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(tr("Teilen", "Share"), Modifier.weight(1f)) { shareCapture(ctx) }
                Action(tr("Leeren", "Clear"), Modifier.weight(1f), primary = false) { DevLog.clear() }
            }
            Spacer(Modifier.height(10.dp))
            Action(tr("Alle Werte lesen", "Read all values"), Modifier.fillMaxWidth(), primary = false) { if (connected) Di2.readAll() }
            Spacer(Modifier.height(10.dp))
            BodyText(
                tr("Vorher den Knopf hinten am Schaltwerk drücken — wie E-TUBE es verlangt. Sonst gibt das Schaltwerk die Einstellungen nicht frei.",
                   "Press the button on the back of the derailleur first — like E-TUBE asks. Otherwise the derailleur does not release its settings."),
                color = P.ink, size = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Action(tr("Schaltwerk-Speicher lesen", "Read derailleur memory"), Modifier.fillMaxWidth(), primary = connected) { if (connected) Di2.readMemory() }
            Spacer(Modifier.height(8.dp))
            BodyText(
                memory ?: if (connected) tr(
                    "Experimentell. Meldet sich wie E-TUBE mit dem Passkey an, öffnet die Sitzung und liest die Einstellungen, die das Schaltwerk daraufhin sendet. Liest nur, ändert nichts.",
                    "Experimental. Logs in like E-TUBE with the passkey, opens the session and reads the settings the derailleur then sends. Reads only, changes nothing.",
                ) else tr("Erst verbinden: einmal schalten.", "Connect first: shift once."),
                color = if (memory != null) P.ink else P.muted, size = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            Label(tr("Schaltmodus schreiben (Test)", "Write shift mode (test)"))
            Spacer(Modifier.height(6.dp))
            BodyText(
                tr("Ändert das Schaltwerk: Vorderumwerfer manuell oder synchronisiert. Reversibel über die Modus-Taste am Lenker. Noch nicht am Rad bestätigt.",
                   "Changes the derailleur: front shifting manual or synchronized. Reversible with the mode button on the bar. Not yet confirmed on a bike."),
                color = P.muted, size = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(tr("Manuell", "Manual"), Modifier.weight(1f), primary = false) { if (connected) Di2.setShiftMode(true) }
                Action(tr("Synchro", "Synchro"), Modifier.weight(1f), primary = false) { if (connected) Di2.setShiftMode(false) }
            }
        }
    }
}

/** Hands the capture to Android's share sheet as a text file. */
private fun shareCapture(ctx: android.content.Context) {
    val f = java.io.File(java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }, "freehub-capture.txt")
    f.writeText("Freehub ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}\n" + DevLog.text() + "\n")
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, tr("Mitschnitt teilen", "Share capture")))
}

/**
 * The derailleur measures no distance: Freehub counts it by GPS while a ride runs.
 * Only the distance is stored, never the route.
 */
@Composable
internal fun Kilometres(km: Double?, loc: Loc, onLocation: () -> Unit) {
    Info(tr("Gesamt", "Total"), km?.let { count(Math.round(it)) + " km" } ?: "–")
    BodyText(
        tr(
            "Freehub misst die Strecke per GPS, solange es eine Fahrt zählt. Gespeichert wird nur die Distanz, nie die Route.",
            "Freehub measures the distance by GPS while it counts a ride. Only the distance is stored, never the route.",
        ),
        Modifier.padding(top = 8.dp), color = P.muted, size = 14.sp,
    )
    when (loc) {
        Loc.NONE -> Action(tr("Standort erlauben", "Allow location"), Modifier.padding(top = 12.dp), onClick = onLocation)
        Loc.IN_USE -> {
            BodyText(
                tr("Misst gerade nur bei offener App. Für jede Fahrt mit Auto-Start: Standort „immer erlauben“.", "Measures only while the app is open. For every ride with auto start: allow location \"all the time\"."),
                Modifier.padding(top = 8.dp), color = P.warn, size = 14.sp,
            )
            Action(tr("Immer erlauben", "Allow all the time"), Modifier.padding(top = 12.dp), primary = false, onClick = onLocation)
        }
        Loc.ALWAYS -> Unit
    }
}

/** Tyre size for the development, tap to step through. */
@Composable
internal fun TyreRow() {
    val ctx = LocalContext.current
    val active by Garage.active.collectAsStateWithLifecycle()
    var wheel by remember(active) { mutableIntStateOf(Prefs.wheel(ctx)) }
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
