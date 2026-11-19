package io.github.brickystudios.freehub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Which firmware Shimano has out for the derailleur, and what came with it. Freehub only tells the rider:
 * installing stays with Shimano's E-TUBE PROJECT Cyclist, and Freehub lets go of the derailleur meanwhile.
 * shortcut: the list ships with the app (from bike.shimano.com/en-EU/e-tube/project/firmware/detail.html),
 * so it is only as new as Freehub; compare it with Shimano's page before each release.
 */
object Firmware {
    val CHECKED: YearMonth = YearMonth.of(2026, 11)
    const val PAUSE_MS = 30 * 60_000L
    private const val ETUBE = "com.shimano.etubeprojectmobile.droid.phone"

    class Release(val version: String, val month: YearMonth, val de: String, val en: String, val levers: Boolean = false)

    /** [news] is everything newer than the installed version, oldest first; empty = up to date */
    class Check(val latest: Release, val news: List<Release>) {
        val levers get() = news.any { it.levers }
    }

    // the 12-speed road and gravel derailleurs; R9250 and R8150 got front shift next as 4.2.0, R7150 as 4.2.1
    private fun road(frontShiftNext: String) = listOf(
        Release("4.1.0", YearMonth.of(2023, 3), "Kann den Kabelschalter EW-EX310, Fehler behoben.", "Works with the EW-EX310 wired switch, bug fixes."),
        Release(frontShiftNext, YearMonth.of(2024, 5),
            "Neue Tastenbelegung FRONT SHIFT NEXT: eine Taste schaltet vorne hoch und runter. Dazu Fehler behoben.",
            "New button function FRONT SHIFT NEXT: one button shifts the front up and down. Plus bug fixes."),
        Release("4.2.3", YearMonth.of(2024, 8),
            "Der Funk zwischen Hebeln und Schaltwerk ist besser gegen fremde Schaltbefehle geschützt.",
            "The radio link between levers and derailleur is better protected against shift commands from someone else.",
            levers = true),
        Release("4.3.0", YearMonth.of(2025, 5),
            "Tasten können per Kabel angeschlossene Geräte anderer Hersteller bedienen (MULTI PURPOSE).",
            "Buttons can work wired devices from other brands (MULTI PURPOSE)."),
        Release("4.3.1", YearMonth.of(2025, 12),
            "Nichts, was du beim Fahren merkst. E-TUBE braucht danach Version 5.4.4 oder neuer.",
            "Nothing you notice on the bike. E-TUBE needs version 5.4.4 or newer afterwards."),
        Release("4.4.0", YearMonth.of(2026, 8),
            "Koppeln klappt nur noch mit passenden Hebeln und Schaltwerken.",
            "Pairing only works with matching levers and derailleurs."),
    )

    private val RELEASES = mapOf(
        "RDR9250" to road("4.2.0"), "RDR8150" to road("4.2.0"),
        "RDR7150" to road("4.2.1"), "RDRX825" to road("4.2.1"),
    )

    /** [name] is what the derailleur advertises ("RDR7150 123"), [installed] what it reports ("4.1.0.0"). null = a model or version Freehub does not know. */
    fun check(name: String?, installed: String?): Check? {
        val list = RELEASES.entries.firstOrNull { name?.replace("-", "")?.startsWith(it.key) == true }?.value ?: return null
        val have = parse(installed) ?: return null
        return Check(list.last(), list.filter { compare(parse(it.version)!!, have) > 0 })
    }

    private fun parse(v: String?): List<Int>? = v?.trim()?.split('.')?.map { it.toIntOrNull() ?: return null }?.takeIf { it.isNotEmpty() }

    /** "4.1.0.0" and "4.1.0" are the same version */
    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun month(m: YearMonth): String = m.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale()))

    /** E-TUBE if it is installed, else its page in the Play Store */
    fun openEtube(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ETUBE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ETUBE"))
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** A quiet row under the firmware field when Shimano has something newer; opens to what's new and how to get it. */
@Composable
fun FirmwareUpdate(bike: Bike, name: String?) {
    val check = Firmware.check(name, bike.firmware)?.takeIf { it.news.isNotEmpty() } ?: return
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    SettingRow(
        tr("Firmware ${check.latest.version} ist da", "Firmware ${check.latest.version} is out"),
        tr("Du hast ${bike.firmware}. Was neu ist und wie du es aufspielst", "You have ${bike.firmware}. What's new and how to install it"),
        onClick = { open = !open },
    ) { BodyText(if (open) tr("Zu", "Close") else tr("Ansehen", "Show"), color = P.accent, weight = FontWeight.SemiBold) }
    if (open) Column(Modifier.padding(bottom = 16.dp)) {
        check.news.forEach { r ->
            Row(Modifier.padding(top = 10.dp)) {
                BodyText(r.version, Modifier.width(56.dp), weight = FontWeight.SemiBold, size = 14.sp)
                Column(Modifier.weight(1f)) {
                    BodyText(tr(r.de, r.en), size = 14.sp)
                    BodyText(Firmware.month(r.month), color = P.faint, size = 13.sp)
                }
            }
        }
        if (check.levers) BodyText(
            tr(
                "Shimano sagt: Damit alles richtig läuft, brauchen Schaltwerk und Hebel beide das Update. Die Hebel gehen nicht per Funk, nur per Kabel vom Hebel ans Schaltwerk oder beim Händler.",
                "Shimano says both derailleur and levers need the update for everything to work. The levers can't be updated over the air, only by cable from lever to derailleur or at a shop.",
            ),
            Modifier.padding(top = 14.dp), color = P.warn, size = 14.sp,
        )
        BodyText(
            tr(
                "Aufspielen kann nur Shimanos App E-TUBE PROJECT Cyclist. Damit sie das Schaltwerk für sich hat, lässt Freehub es 30 Minuten in Ruhe.",
                "Only Shimano's E-TUBE PROJECT Cyclist app can install it. So it has the derailleur to itself, Freehub leaves it alone for 30 minutes.",
            ),
            Modifier.padding(top = 14.dp), color = P.muted, size = 14.sp,
        )
        if (!bike.demo) Action(tr("In E-TUBE aufspielen", "Install in E-TUBE"), Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Di2.pause(ctx, Firmware.PAUSE_MS)
            Firmware.openEtube(ctx)
        }
        BodyText(tr("Shimanos Liste, Stand ", "Shimano's list as of ") + Firmware.month(Firmware.CHECKED), Modifier.padding(top = 10.dp), color = P.faint, size = 13.sp)
    }
    Divider()
}
