package io.github.brickystudios.freehub

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
    /** GPS distance while this ride was counted; 0 without location access */
    var meters = 0.0
    val usage = HashMap<Gear, Long>()
    /** rear shifts that went one cog and came straight back, by direction: see [Shifting] */
    var takenBackUp = 0
        private set
    var takenBackDown = 0
        private set
    private var last: Gear? = null
    private var before: Gear? = null
    private var lastShiftAt = 0L
    private var lastAt = start
    private var linked = false

    val shifts get() = rearShifts + frontShifts
    val ms get() = usage.values.sum()

    fun onGear(g: Gear, now: Long) {
        tick(now)
        val from = last
        if (from != null && g != from) {
            rearShifts += abs(g.rear - from.rear)
            frontShifts += abs(g.front - from.front)
            // one cog over and straight back again: that shift did not hold
            if (g == before && g.front == from.front && abs(g.rear - from.rear) == 1 && now - lastShiftAt <= TAKEN_BACK_MS) {
                if (from.rear > g.rear) takenBackUp++ else takenBackDown++
            }
            before = from
            lastShiftAt = now
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
        .put("m", meters).put("usage", usage.toJson())
        .put("backUp", takenBackUp).put("backDown", takenBackDown)

    companion object {
        /** a shift and its reversal this close together was not the rider changing their mind */
        const val TAKEN_BACK_MS = 1_500L

        fun fromJson(o: JSONObject) = Ride(o.getLong("start")).apply {
            end = o.getLong("end")
            rearShifts = o.getInt("rear")
            frontShifts = o.getInt("front")
            meters = o.optDouble("m", 0.0)
            usage.putAll(o.optJSONObject("usage").toUsage())
            takenBackUp = o.optInt("backUp")
            takenBackDown = o.optInt("backDown")
        }
    }
}

/** Everything ever counted, plus the recent rides. */
data class Totals(
    val rearShifts: Long = 0,
    val frontShifts: Long = 0,
    val usage: Map<Gear, Long> = emptyMap(),
    val rides: List<Ride> = emptyList(),
    /** every GPS metre ever counted, also from rides that dropped off the list */
    val meters: Double = 0.0,
    /** rear shifts the derailleur took back by itself, by direction: see [Shifting] */
    val takenBackUp: Long = 0,
    val takenBackDown: Long = 0,
) {
    val shifts get() = rearShifts + frontShifts
    val ms get() = usage.values.sum()

    /** Rides shorter than a minute with no shift are noise from a quick look at the app. */
    fun plus(r: Ride): Totals = if (r.shifts == 0 && r.ms < 60_000) this else copy(
        rearShifts = rearShifts + r.rearShifts,
        frontShifts = frontShifts + r.frontShifts,
        usage = (usage.keys + r.usage.keys).associateWith { (usage[it] ?: 0) + (r.usage[it] ?: 0) },
        rides = (listOf(r) + rides).take(MAX_RIDES),
        meters = meters + r.meters,
        takenBackUp = takenBackUp + r.takenBackUp,
        takenBackDown = takenBackDown + r.takenBackDown,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("rear", rearShifts).put("front", frontShifts).put("m", meters).put("usage", usage.toJson())
        .put("backUp", takenBackUp).put("backDown", takenBackDown)
        .put("rides", JSONArray().apply { rides.forEach { put(it.toJson()) } })

    companion object {
        const val MAX_RIDES = 200

        fun fromJson(o: JSONObject) = Totals(
            rearShifts = o.optLong("rear"),
            frontShifts = o.optLong("front"),
            usage = o.optJSONObject("usage").toUsage(),
            meters = o.optDouble("m", 0.0),
            takenBackUp = o.optLong("backUp"),
            takenBackDown = o.optLong("backDown"),
            rides = o.optJSONArray("rides")?.let { a -> (0 until a.length()).map { Ride.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/**
 * How cleanly the rear derailleur shifts, from the shifts Freehub already counts. A shift that goes one cog and
 * comes straight back within [Ride.TAKEN_BACK_MS] did not hold: either the chain did not climb the cog, or it
 * dropped back. A few of those are normal riding; a steady share of them is what Shimano's fine-tune is for.
 *
 * Freehub only reads — it never writes an adjustment to the derailleur. Pure, so the JVM tests cover it.
 */
object Shifting {
    /** below this many rear shifts there is nothing to judge */
    const val ENOUGH = 60L
    /** from this share of shifts taken back on, the fine-tune is worth a look */
    const val WORTH_A_LOOK = 0.04f

    data class Health(val rearShifts: Long, val up: Long, val down: Long) {
        val takenBack get() = up + down
        val share get() = if (rearShifts > 0) takenBack.toFloat() / rearShifts else 0f
        val enough get() = rearShifts >= ENOUGH
        val clean get() = enough && share < WORTH_A_LOOK
        val worthALook get() = enough && share >= WORTH_A_LOOK
        /** where the chain struggles: true towards the small cogs (harder), false towards the large ones (easier) */
        val towardsSmallCogs get() = up >= down
    }

    /** The ride in progress counts too, so the reading moves while you ride. */
    fun health(t: Totals, ride: RideNow?): Health = Health(
        rearShifts = t.rearShifts + (ride?.rear ?: 0),
        up = t.takenBackUp + (ride?.takenBackUp ?: 0),
        down = t.takenBackDown + (ride?.takenBackDown ?: 0),
    )
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
