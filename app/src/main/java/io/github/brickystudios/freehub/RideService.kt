package io.github.brickystudios.freehub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the derailleur link while the phone sleeps in the jersey pocket, so every shift is counted.
 * Ends itself when the ride ends ([Counter.END_AFTER_MS] without a link) or on "Stop".
 */
class RideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Counter.finish()
            Di2.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        Lang.init(this)
        if (intent?.action == AUTO) {
            // woken by Android with Freehub closed: set up what the app would, then look for the derailleur
            Garage.init(this)
            DevLog.on = Prefs.dev(this)
            Di2.start(this)
            // the derailleur showed up but no ride came of it (someone moved the bike): give up quietly
            scope.launch {
                delay(Counter.END_AFTER_MS)
                if (Counter.now.value == null) { if (!MainActivity.visible) Di2.stop(); stopSelf() }
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Fahrt", "Ride"), NotificationManager.IMPORTANCE_LOW))
        val first = note(texts(Di2.bike.value, Counter.now.value))
        // with location access the service also counts the distance; Android refuses that type when started
        // from the background without "allow all the time", so fall back to Bluetooth alone
        if (Build.VERSION.SDK_INT >= 29) {
            val bt = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val gps = Gps.access(this) != Loc.NONE &&
                runCatching { startForeground(ID, first, bt or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }.isSuccess
            if (!gps) startForeground(ID, first, bt) else Gps.start(this)
        } else {
            startForeground(ID, first)
            Gps.start(this)
        }
        if (job == null) job = scope.launch {
            var rode = false
            var shown: Pair<String, String>? = null
            combine(Di2.bike, Counter.now) { b, r -> b to r }
                .collect { (b, r) ->
                    if (r != null) rode = true
                    if (rode && r == null) { // the ride ended: stop looking for the derailleur, unless someone is watching
                        if (!MainActivity.visible) Di2.stop()
                        stopSelf()
                        return@collect
                    }
                    // the ride ticks every second; the notification only changes when its text does
                    val t = texts(b, r)
                    if (t != shown) { shown = t; nm.notify(ID, note(t)) }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Gps.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun texts(b: Bike, r: RideNow?): Pair<String, String> {
        val g = b.gears
        val gear = if (g?.frontTeeth != null && g.rearTeeth != null) "${g.frontTeeth} × ${g.rearTeeth}" else tr("Warte auf Schaltwerk", "Waiting for derailleur")
        val title = gear + (b.battery?.let { " · " + tr("Akku", "Battery") + " $it %" } ?: "")
        val text = r?.let { count((it.rear + it.front).toLong()) + " " + tr("Schaltvorgänge", "shifts") + " · " + duration(it.ms) }
            ?: tr("Zählt, sobald du schaltest", "Counts as soon as you shift")
        return title to text
    }

    private fun note(t: Pair<String, String>): Notification {
        val (title, text) = t
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, tr("Fahrt beenden", "End ride"), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val ID = 1
        private const val STOP = "stop"
        private const val AUTO = "auto"

        /** The derailleur woke up while Freehub was closed. */
        fun wake(c: Context) {
            if (!MainActivity.hasPermissions(c) || Di2.bike.value.demo || Di2.paused(c)) return
            runCatching { ContextCompat.startForegroundService(c, Intent(c, RideService::class.java).setAction(AUTO)) }
                .onFailure { Log.w("Freehub", "auto start refused", it) }
        }
    }
}
