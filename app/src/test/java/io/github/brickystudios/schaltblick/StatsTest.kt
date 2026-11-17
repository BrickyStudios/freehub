package io.github.brickystudios.schaltblick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StatsTest {
    @Test fun countsEveryCogAndTimePerGear() {
        val r = Ride(0)
        r.onGear(Gear(2, 7), 0)
        r.tick(10_000)
        r.onGear(Gear(2, 9), 12_000) // held: two cogs at once
        r.onGear(Gear(1, 10), 15_000) // synchro: both derailleurs move
        r.tick(20_000)
        assertEquals(3, r.rearShifts)
        assertEquals(1, r.frontShifts)
        assertEquals(12_000L, r.usage[Gear(2, 7)])
        assertEquals(3_000L, r.usage[Gear(2, 9)])
        assertEquals(5_000L, r.usage[Gear(1, 10)])
        assertEquals(20_000L, r.end)
    }

    @Test fun lostLinkCountsNoTimeButLaterShifts() {
        val r = Ride(0)
        r.onGear(Gear(2, 7), 0)
        r.pause(5_000)
        r.tick(60_000)
        r.onGear(Gear(2, 4), 90_000)
        r.tick(95_000)
        assertEquals(5_000L, r.usage[Gear(2, 7)])
        assertEquals(5_000L, r.usage[Gear(2, 4)])
        assertEquals(3, r.rearShifts)
    }

    @Test fun totalsSkipNoiseAndRoundTrip() {
        val empty = Totals()
        val glance = Ride(0).apply { onGear(Gear(1, 1), 0); tick(30_000) }
        assertSame(empty, empty.plus(glance))

        val ride = Ride(1_000).apply { onGear(Gear(2, 6), 1_000); onGear(Gear(2, 8), 61_000); tick(121_000) }
        val t = empty.plus(ride).plus(ride)
        assertEquals(4L, t.rearShifts)
        assertEquals(240_000L, t.ms)

        val back = Totals.fromJson(org.json.JSONObject(t.toJson().toString()))
        assertEquals(t.rearShifts, back.rearShifts)
        assertEquals(t.usage, back.usage)
        assertEquals(2, back.rides.size)
        assertEquals(ride.usage, back.rides[0].usage)
    }

    @Test fun crossChainingAndFavourite() {
        val u = mapOf(Gear(2, 1) to 10L, Gear(2, 6) to 70L, Gear(1, 12) to 20L)
        assertEquals(0.3f, Usage.crossShare(u, 2, 12), 1e-6f)
        assertEquals(0f, Usage.crossShare(u, 1, 12), 1e-6f)
        assertEquals(Gear(2, 6), Usage.favourite(u))
        assertEquals(0.7f, Usage.shares(u)[Gear(2, 6)]!!, 1e-6f)
    }
}
