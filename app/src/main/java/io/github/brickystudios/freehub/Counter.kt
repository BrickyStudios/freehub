package io.github.brickystudios.freehub

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** What the screens show of the ride in progress. */
data class RideNow(
    val start: Long, val rear: Int, val front: Int, val ms: Long, val usage: Map<Gear, Long>, val meters: Double = 0.0,
    val takenBackUp: Int = 0, val takenBackDown: Int = 0,
)

/**
 * Owns the ride in progress and the totals. A ride starts with the first gear report and ends
 * after [END_AFTER_MS] without a link, which is longer than the derailleur's sleep after a stop at the lights.
 * Demo mode counts into memory only, so it never touches real numbers.
 */
object Counter {
    const val END_AFTER_MS = 10 * 60_000L
    private const val SAVE_EVERY_MS = 30_000L

    val totals = MutableStateFlow(Totals())
    val now = MutableStateFlow<RideNow?>(null)

    /** this bike's statistics; null while no bike is paired (nothing to count then) */
    private var file: File? = null
    private var key: String? = null
    private var opened = false
    private val main = Handler(Looper.getMainLooper())
    private var ride: Ride? = null
    private var demo = false
    private var lastSave = 0L
    private val track = Track()

    /** Switches to a bike's statistics (see [Garage]). A ride in progress is saved to the bike it ran on first. */
    fun open(ctx: Context, key: String?) {
        if (opened && key == this.key) return
        finish()
        opened = true
        this.key = key
        file = key?.let { File(ctx.filesDir, "stats-$it.json") }
        if (demo) return // the demo keeps its sample; the real numbers load when it ends
        totals.value = Totals()
        val o = runCatching { JSONObject(file!!.readText()) }.getOrNull() ?: return
        var t = Totals.fromJson(o)
        // a ride the system cut short when it ended the app: keep what was counted
        o.optJSONObject("open")?.let { t = t.plus(Ride.fromJson(it)) }
        totals.value = t
        if (o.has("open")) save()
    }

    fun setDemo(on: Boolean, sample: Totals) {
        if (on == demo) return
        finish()
        demo = on
        totals.value = if (on) sample else load()
    }

    fun onGears(g: Gears) {
        val rear = g.rear ?: return
        val t = System.currentTimeMillis()
        val r = ride ?: Ride(t).also { ride = it; track.reset(); startTicking() }
        r.onGear(Gear(g.front ?: 1, rear), t)
        publish(r)
    }

    fun linked() = main.removeCallbacks(endRide)

    /** A GPS fix while the ride runs; only the distance is kept. */
    fun onFix(lat: Double, lon: Double, accuracy: Float, time: Long) {
        val r = ride ?: return
        val d = track.add(lat, lon, accuracy, time)
        if (d > 0) { r.meters += d; publish(r) }
    }

    /** the bike's distance so far in km, the ride in progress included; null before the first GPS metre */
    fun km(): Double? = (totals.value.meters + (ride?.meters ?: 0.0)).takeIf { it > 0 }?.div(1000)

    fun linkLost() {
        val r = ride ?: return
        r.pause(System.currentTimeMillis())
        publish(r)
        main.removeCallbacks(endRide)
        main.postDelayed(endRide, END_AFTER_MS)
    }

    /** Ends the ride now and adds it to the totals. */
    fun finish() {
        main.removeCallbacks(endRide)
        main.removeCallbacks(tick)
        val r = ride ?: return
        ride = null
        now.value = null
        r.tick(System.currentTimeMillis())
        totals.value = totals.value.plus(r)
        save()
    }

    fun reset() {
        finish()
        totals.value = Totals()
        save()
    }

    private val endRide = Runnable { finish() }

    private val tick = object : Runnable {
        override fun run() {
            val r = ride ?: return
            r.tick(System.currentTimeMillis())
            publish(r)
            main.postDelayed(this, 1_000)
        }
    }

    private fun startTicking() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000)
    }

    private fun publish(r: Ride) {
        now.value = RideNow(r.start, r.rearShifts, r.frontShifts, r.ms, HashMap(r.usage), r.meters, r.takenBackUp, r.takenBackDown)
        if (System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) save()
    }

    private fun load() = runCatching { Totals.fromJson(JSONObject(file!!.readText())) }.getOrDefault(Totals())

    /** Writes to a temp file first, so a crash mid-write never leaves half a file. */
    private fun save() {
        val f = file ?: return
        if (demo) return
        lastSave = System.currentTimeMillis()
        val o = totals.value.toJson()
        ride?.let { o.put("open", it.toJson()) }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }
}
