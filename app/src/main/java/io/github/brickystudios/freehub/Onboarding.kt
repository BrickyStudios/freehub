package io.github.brickystudios.freehub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Step { WELCOME, BLUETOOTH, CONNECT, DRIVETRAIN, AUTO, GPS, DONE }

/**
 * The first start: one screen per thing Freehub needs, so every feature works from the first ride.
 * Each step can be skipped; everything stays changeable under Bike. Runs again from Bike → Settings.
 */
@Composable
fun Onboarding(
    bike: Bike, gears: Gears?, granted: Boolean, autoStart: Boolean?, loc: Loc,
    onAllow: () -> Unit, onBluetooth: () -> Unit, onAutoStart: (Boolean) -> Unit, onLocation: () -> Unit,
    paired: Boolean, onPaired: (Profile) -> Unit, onDone: () -> Unit,
) {
    val steps = Step.entries.filter { it != Step.AUTO || autoStart != null }
    var i by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[i]
    fun next() { if (i < steps.lastIndex) i++ else onDone() }

    // move on by itself once the thing a step asks for has happened
    LaunchedEffect(step, granted) { if (step == Step.BLUETOOTH && granted) next() }
    LaunchedEffect(step, autoStart) { if (step == Step.AUTO && autoStart == true) next() }
    LaunchedEffect(step, loc) { if (step == Step.GPS && loc == Loc.ALWAYS) next() }

    Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.indices.forEach { Spacer(Modifier.width(if (it == i) 22.dp else 6.dp).height(6.dp).background(if (it <= i) P.accent else P.track, Pill)) }
        }
        Spacer(Modifier.height(28.dp))
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "step") { s ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (s) {
                    Step.WELCOME -> {
                        Ratchet(Modifier.size(72.dp))
                        Spacer(Modifier.height(28.dp))
                        Title(tr("Dein Di2,\nauf einen Blick", "Your Di2,\nat a glance"))
                        Text(tr(
                            "Gang, Akku, Reichweite, Kilometer und jeder Schaltvorgang, direkt vom Schaltwerk. In ein paar Schritten ist alles eingerichtet; jeder lässt sich überspringen.",
                            "Gear, battery, range, distance and every shift, straight from the derailleur. A few steps set everything up; each one can be skipped.",
                        ))
                    }
                    Step.BLUETOOTH -> {
                        Title(tr("Bluetooth erlauben", "Allow Bluetooth"))
                        Text(tr(
                            "Freehub spricht per Bluetooth mit dem Schaltwerk. Mitteilungen braucht die Zählung, während das Handy in der Trikottasche steckt.",
                            "Freehub talks to the derailleur over Bluetooth. Notifications keep the counting going while the phone sits in your jersey pocket.",
                        ))
                    }
                    Step.CONNECT -> if (!paired && bike.link != Link.NO_BLUETOOTH) {
                        Title(tr("Dein Rad koppeln", "Pair your bike"))
                        Text(tr(
                            "Schalt einmal an deinem Rad und tippe sein Schaltwerk an. Freehub verbindet sich danach nur noch mit gekoppelten Rädern, ein gleiches Rad daneben bleibt unberührt.",
                            "Shift once on your bike and tap its derailleur. From then on Freehub only connects to paired bikes; the same bike next to it stays untouched.",
                        ))
                        PairList(onPaired)
                    } else {
                        Title(if (bike.link == Link.CONNECTED) tr("Verbunden", "Connected") else tr("Einmal schalten", "Shift once"))
                        Text(
                            when (bike.link) {
                                Link.CONNECTED -> (model(bike.name) ?: "Di2") + (bike.battery?.let { " · " + tr("Akku", "battery") + " $it %" } ?: "")
                                Link.NO_BLUETOOTH -> tr("Bluetooth ist aus.", "Bluetooth is off.")
                                else -> tr("Das Schaltwerk schläft, wenn das Rad steht. Ein Klick am Hebel weckt es, dann verbindet sich Freehub von selbst.", "The derailleur sleeps while the bike stands still. One click on a lever wakes it, then Freehub connects on its own.")
                            },
                        )
                        if (bike.link != Link.CONNECTED) {
                            Spacer(Modifier.height(24.dp))
                            Ratchet(Modifier.size(120.dp), coasting = true)
                        }
                    }
                    Step.DRIVETRAIN -> {
                        Title(tr("Kurbel und Kassette", "Crankset and cassette"))
                        Text(tr(
                            "Damit Freehub Zähne, Übersetzung und Entfaltung zeigt. Viele Schaltwerke kennen ihre Teile nicht: steht „unbekannt“, tippe, bis deine erscheinen.",
                            "So Freehub can show teeth, ratio and development. Many derailleurs do not know their parts: if it says \"unknown\", tap until yours show up.",
                        ))
                        Spacer(Modifier.height(12.dp))
                        DrivetrainPicker(bike, gears)
                        TyreRow()
                    }
                    Step.AUTO -> {
                        Title(tr("Jede Fahrt zählen", "Count every ride"))
                        Text(tr(
                            "Freehub startet von selbst, sobald das Schaltwerk aufwacht, auch wenn die App zu ist. Android fragt einmal nach dem Gerät: einmal schalten, dann dein Schaltwerk antippen.",
                            "Freehub starts on its own as soon as the derailleur wakes up, even with the app closed. Android asks once for the device: shift once, then tap your derailleur.",
                        ))
                        if (!granted) Text(tr("Dafür braucht Freehub zuerst Bluetooth.", "This needs Bluetooth first."), warn = true)
                    }
                    Step.GPS -> {
                        Title(tr("Kilometer messen", "Measure distance"))
                        Text(tr(
                            "Das Schaltwerk misst keine Strecke. Freehub misst sie per GPS, solange es eine Fahrt zählt. Gespeichert wird nur die Distanz, nie die Route. Daraus lernt auch die Akku-Reichweite.",
                            "The derailleur measures no distance. Freehub measures it by GPS while it counts a ride. Only the distance is stored, never the route. The battery range learns from it too.",
                        ))
                        if (loc == Loc.IN_USE) Text(tr(
                            "Jetzt noch „immer erlauben“, damit es auch mit geschlossener App misst.",
                            "Now allow it \"all the time\", so it measures with the app closed too.",
                        ), warn = true)
                    }
                    Step.DONE -> {
                        Title(tr("Fertig", "Done"))
                        Text(tr("Alles lässt sich später unter Rad ändern, auch diese Einrichtung.", "Everything can be changed later under Bike, this setup too."))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val button: Pair<String, () -> Unit> = when (step) {
            Step.WELCOME -> tr("Los geht's", "Get started") to { next() }
            Step.BLUETOOTH -> if (granted) tr("Weiter", "Next") to { next() } else tr("Erlauben", "Allow") to onAllow
            Step.CONNECT -> when (bike.link) {
                Link.CONNECTED -> tr("Weiter", "Next") to { next() }
                Link.NO_BLUETOOTH -> tr("Bluetooth einschalten", "Turn on Bluetooth") to onBluetooth
                else -> (if (paired) tr("Später verbinden", "Connect later") else tr("Später koppeln", "Pair later")) to { next() }
            }
            Step.GPS -> when (loc) {
                Loc.NONE -> tr("Standort erlauben", "Allow location") to onLocation
                Loc.IN_USE -> tr("Immer erlauben", "Allow all the time") to onLocation
                Loc.ALWAYS -> tr("Weiter", "Next") to { next() }
            }
            Step.AUTO -> if (granted) tr("Einschalten", "Switch on") to { onAutoStart(true) } else tr("Weiter", "Next") to { next() }
            Step.DONE -> tr("Freehub öffnen", "Open Freehub") to onDone
            else -> tr("Weiter", "Next") to { next() }
        }
        // where the main button already says "later", a second skip button would do the same thing; and it stays
        // quiet, so the derailleur in the list is the thing to tap
        val waiting = step == Step.CONNECT && bike.link != Link.CONNECTED && bike.link != Link.NO_BLUETOOTH
        Action(button.first, Modifier.fillMaxWidth(), primary = !waiting, onClick = button.second)
        if (step !in setOf(Step.WELCOME, Step.DONE) && !waiting) {
            Spacer(Modifier.height(12.dp))
            Action(tr("Überspringen", "Skip"), Modifier.fillMaxWidth(), primary = false) { next() }
        }
    }
}

@Composable
private fun Title(text: String) {
    Num(text, 44.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Text(text: String, warn: Boolean = false) =
    BodyText(text, Modifier.padding(bottom = 8.dp), color = if (warn) P.warn else P.muted, size = 17.sp, weight = if (warn) FontWeight.Medium else FontWeight.Normal)
