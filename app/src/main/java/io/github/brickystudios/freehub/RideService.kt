package io.github.brickystudios.freehub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Fahrt", "Ride"), NotificationManager.IMPORTANCE_LOW))
        val first = note(texts(Di2.bike.value, Counter.now.value))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, first)
        if (job == null) job = scope.launch {
            var rode = false
            var shown: Pair<String, String>? = null
            combine(Di2.bike, Counter.now) { b, r -> b to r }
                .collect { (b, r) ->
                    if (r != null) rode = true
                    if (rode && r == null) { // the ride ended: stop looking for the derailleur
                        Di2.stop()
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
    }
}
