package io.github.brickystudios.schaltblick

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A gear as positions (1 = easiest on both derailleurs). */
data class Gear(val front: Int, val rear: Int) {
    val key get() = "$front:$rear"

    companion object {
        fun of(key: String) = key.split(':').let { Gear(it[0].toInt(), it[1].toInt()) }
    }
}

/**
 * Shifts and time per gear, counted from the stream of gear reports. Pure, so the JVM tests cover it.
 * Every cog the chain passes counts as one shift, so a held multi-shift over three cogs counts three.
 */
class Ride(val start: Long) {
    var end = start
        private set
    var rearShifts = 0
        private set
    var frontShifts = 0
        private set
    val usage = HashMap<Gear, Long>()
    private var last: Gear? = null
    private var lastAt = start
    private var linked = false

    val shifts get() = rearShifts + frontShifts
    val ms get() = usage.values.sum()

    fun onGear(g: Gear, now: Long) {
        tick(now)
        last?.let {
            rearShifts += abs(g.rear - it.rear)
            frontShifts += abs(g.front - it.front)
        }
        last = g
        linked = true
        end = now
    }

    /** Adds the time since the last call to the current gear, as long as the derailleur is connected. */
    fun tick(now: Long) {
        val g = last
        if (linked && g != null && now > lastAt) usage.merge(g, now - lastAt, Long::plus)
        lastAt = now
        if (linked) end = now
    }

    /** Link lost: no gear gets the time until the next report. Shifts made meanwhile still count then. */
    fun pause(now: Long) {
        tick(now)
        linked = false
    }

    fun toJson(): JSONObject = JSONObject()
        .put("start", start).put("end", end).put("rear", rearShifts).put("front", frontShifts)
        .put("usage", usage.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Ride(o.getLong("start")).apply {
            end = o.getLong("end")
            rearShifts = o.getInt("rear")
            frontShifts = o.getInt("front")
            usage.putAll(o.optJSONObject("usage").toUsage())
        }
    }
}

/** Everything ever counted, plus the recent rides. */
data class Totals(
    val rearShifts: Long = 0,
    val frontShifts: Long = 0,
    val usage: Map<Gear, Long> = emptyMap(),
    val rides: List<Ride> = emptyList(),
) {
    val shifts get() = rearShifts + frontShifts
    val ms get() = usage.values.sum()

    /** Rides shorter than a minute with no shift are noise from a quick look at the app. */
    fun plus(r: Ride): Totals = if (r.shifts == 0 && r.ms < 60_000) this else copy(
        rearShifts = rearShifts + r.rearShifts,
        frontShifts = frontShifts + r.frontShifts,
        usage = (usage.keys + r.usage.keys).associateWith { (usage[it] ?: 0) + (r.usage[it] ?: 0) },
        rides = (listOf(r) + rides).take(MAX_RIDES),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("rear", rearShifts).put("front", frontShifts).put("usage", usage.toJson())
        .put("rides", JSONArray().apply { rides.forEach { put(it.toJson()) } })

    companion object {
        const val MAX_RIDES = 200

        fun fromJson(o: JSONObject) = Totals(
            rearShifts = o.optLong("rear"),
            frontShifts = o.optLong("front"),
            usage = o.optJSONObject("usage").toUsage(),
            rides = o.optJSONArray("rides")?.let { a -> (0 until a.length()).map { Ride.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/** Read-outs over a usage map. */
object Usage {
    /** share of the time per gear, 0..1 */
    fun shares(usage: Map<Gear, Long>): Map<Gear, Float> {
        val total = usage.values.sum().takeIf { it > 0 } ?: return emptyMap()
        return usage.mapValues { it.value.toFloat() / total }
    }

    /**
     * Time spent cross-chained: big ring on the two largest cogs, or small ring on the two smallest.
     * Needs at least two rings; a 1x drivetrain has no cross-chaining in this sense.
     */
    fun crossShare(usage: Map<Gear, Long>, frontMax: Int, rearMax: Int): Float {
        if (frontMax < 2) return 0f
        val total = usage.values.sum().takeIf { it > 0 } ?: return 0f
        val cross = usage.filterKeys { (it.front == frontMax && it.rear <= 2) || (it.front == 1 && it.rear >= rearMax - 1) }
        return cross.values.sum().toFloat() / total
    }

    fun favourite(usage: Map<Gear, Long>): Gear? = usage.maxByOrNull { it.value }?.key
}

private fun Map<Gear, Long>.toJson() = JSONObject().apply { forEach { (g, ms) -> put(g.key, ms) } }

private fun JSONObject?.toUsage(): Map<Gear, Long> =
    this?.keys()?.asSequence()?.associate { Gear.of(it) to getLong(it) } ?: emptyMap()
