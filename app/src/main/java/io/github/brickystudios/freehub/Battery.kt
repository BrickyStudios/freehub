package io.github.brickystudios.freehub

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One battery reading. km = the GPS distance Freehub had counted by then, null before the first GPS metre. */
data class Charge(val at: Long, val percent: Int, val km: Double?)

/** How far the battery goes. Pure, so the JVM tests cover it. */
object Range {
    /** Shimano rates 105 Di2 at about 1,000 km per charge: 10 km per percent until we know better. */
    const val SHIMANO_KM_PER_PERCENT = 10.0
    /** a smaller drop is too coarse to learn from: the derailleur reports whole percent */
    private const val MIN_DROP = 5
    private const val CHARGE_JUMP = 5

    /**
     * km per percent, summed over all stretches without a charge in between where the odometer is known.
     * A single stretch can show a drop without km (a ride without location access); the sum evens that out. Null until at least [MIN_DROP] percent were used.
     */
    fun learned(log: List<Charge>): Double? {
        var km = 0.0
        var drop = 0
        log.zipWithNext { a, b ->
            if (b.percent <= a.percent && a.km != null && b.km != null && b.km >= a.km) {
                km += b.km - a.km
                drop += a.percent - b.percent
            }
        }
        return if (drop >= MIN_DROP && km > 0) km / drop else null
    }

    fun kmPerPercent(log: List<Charge>) = learned(log) ?: SHIMANO_KM_PER_PERCENT

    fun remainingKm(percent: Int, log: List<Charge>) = percent * kmPerPercent(log)

    /** When the level last jumped up: that was a charge. */
    fun lastCharged(log: List<Charge>): Long? =
        log.zipWithNext().lastOrNull { (a, b) -> b.percent >= a.percent + CHARGE_JUMP }?.second?.at

    fun toJson(log: List<Charge>) = JSONArray().apply {
        log.forEach { put(JSONArray().put(it.at).put(it.percent).put(it.km ?: JSONObject.NULL)) }
    }.toString()

    fun fromJson(s: String): List<Charge> {
        val a = JSONArray(s)
        return (0 until a.length()).map { i ->
            val e = a.getJSONArray(i)
            Charge(e.getLong(0), e.getInt(1), if (e.isNull(2)) null else e.getDouble(2))
        }
    }
}

/**
 * Keeps every battery reading, also from rides where Freehub only ran in the background,
 * so the range estimate and "last charged" survive an app restart.
 */
object Power {
    private const val MAX = 2_000

    val log = MutableStateFlow<List<Charge>>(emptyList())
    /** this bike's battery log; null while no bike is paired */
    private var file: File? = null

    /** Switches to a bike's battery log (see [Garage]). */
    fun open(ctx: Context, key: String?) {
        file = key?.let { File(ctx.filesDir, "battery-$it.json") }
        log.value = file?.let { f -> runCatching { Range.fromJson(f.readText()) }.getOrNull() } ?: emptyList()
    }

    /** Logged only when the level or the odometer moved, so the file stays small. */
    fun record(percent: Int, km: Double?, now: Long = System.currentTimeMillis()) {
        val f = file ?: return
        if (percent !in 0..100) return
        val last = log.value.lastOrNull()
        if (last != null && last.percent == percent && (km == null || km == last.km)) return
        log.value = (log.value + Charge(now, percent, km)).takeLast(MAX)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Range.toJson(log.value))
        tmp.renameTo(f)
    }
}
