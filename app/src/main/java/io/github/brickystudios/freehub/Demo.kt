package io.github.brickystudios.freehub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** A made-up 105 Di2 (50/34, 11-34) on a rolling ride. Same data path as the real derailleur. */
object Demo {
    private var job: Job? = null

    fun gears(g: Gear) = Gears(
        mode = ShiftMode.SYNCHRO_S1, multiShiftInterval = null,
        front = g.front, frontMax = 2, rear = g.rear, rearMax = 12,
        frontAdjust = 0, rearAdjust = 0, rearAdjustMin = -8, rearAdjustMax = 8,
        chainrings = Teeth.chainrings(6), sprockets = Teeth.sprockets(18),
    )

    /**
     * A rider who cruises on the big ring around the middle of the cassette and now and then climbs.
     * Synchro S1 drops to the small ring near the largest cogs and back up near the smallest.
     */
    private class Rider(private val rnd: Random) {
        var gear = Gear(2, 7)
        private var target = 7
        private var climb = 0

        fun next(): Gear {
            if (climb == 0 && rnd.nextInt(14) == 0) climb = rnd.nextInt(4, 9)
            target = if (climb > 0) { climb--; 3 } else if (rnd.nextInt(4) == 0) rnd.nextInt(5, 11) else target
            var front = gear.front
            var rear = gear.rear + when {
                want(target) > gear.rear -> if (rnd.nextInt(3) == 0) 2 else 1
                want(target) < gear.rear -> if (rnd.nextInt(3) == 0) -2 else -1
                else -> listOf(-1, 1).random(rnd)
            }
            rear = rear.coerceIn(1, 12)
            if (front == 2 && rear <= 3 && climb > 0) { front = 1; rear += 3 }
            else if (front == 1 && rear >= 10) { front = 2; rear -= 3 }
            gear = Gear(front, rear)
            return gear
        }

        // on the small ring the same effort sits three cogs further up
        private fun want(t: Int) = if (gear.front == 1) (t + 3).coerceAtMost(12) else t
    }

    fun start(scope: CoroutineScope) {
        job?.cancel()
        Counter.setDemo(true, sample())
        Strava.setDemo(true)
        Di2.setDemo(true)
        job = scope.launch {
            val rnd = Random(7)
            val rider = Rider(rnd)
            var battery = 82
            var seq = 0
            while (isActive) {
                Di2.feed(Bike(Link.CONNECTED, "RDR7150 000", battery, gears(rider.gear), "4.2.1", "DEMO-0000", Di2.bike.value.buttons, demo = true))
                delay(rnd.nextLong(2_500, 6_000))
                rider.next()
                if (rnd.nextInt(40) == 0) battery = (battery - 1).coerceAtLeast(5)
                if (rnd.nextInt(8) == 0) {
                    seq = (seq + 1) and 0x0F
                    Di2.bike.value = Di2.bike.value.copy(buttons = Instant.Buttons(seq, mapOf(1 to Press.SHORT)))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Di2.setDemo(false)
        Counter.setDemo(false, Totals())
        Strava.setDemo(false)
    }

    /** Twelve earlier rides for the statistics screen. */
    fun sample(): Totals {
        val rnd = Random(11)
        val day = 24 * 3_600_000L
        val today = System.currentTimeMillis() / day * day
        var t = Totals()
        for (i in 12 downTo 1) {
            val start = today - i * 3 * day + 17 * 3_600_000L
            val r = Ride(start)
            val rider = Rider(rnd)
            var now = start
            val end = start + rnd.nextInt(55, 170) * 60_000L
            while (now < end) {
                r.onGear(rider.gear, now)
                now += rnd.nextLong(15_000, 70_000)
                rider.next()
            }
            r.tick(now)
            t = t.plus(r)
        }
        return t
    }
}
