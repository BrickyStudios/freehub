package io.github.brickystudios.freehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun shiftsTakenBackRightAwayCount() {
        val r = Ride(0)
        r.onGear(Gear(2, 7), 0)
        r.onGear(Gear(2, 8), 10_000) // to a smaller cog …
        r.onGear(Gear(2, 7), 10_600) // … and straight back: did not hold
        r.onGear(Gear(2, 6), 20_000) // to a larger cog …
        r.onGear(Gear(2, 7), 20_900) // … and back
        r.onGear(Gear(2, 8), 40_000) // the same move, but the rider waited: their own choice
        r.onGear(Gear(2, 7), 45_000)
        r.onGear(Gear(2, 9), 60_000) // two cogs at once, back again: a multi-shift, not a hitch
        r.onGear(Gear(2, 7), 60_500)
        assertEquals(1, r.takenBackUp)
        assertEquals(1, r.takenBackDown)
        assertEquals(10, r.rearShifts)

        val back = Ride.fromJson(org.json.JSONObject(r.toJson().toString()))
        assertEquals(1, back.takenBackUp)
        assertEquals(1, back.takenBackDown)
    }

    @Test fun shiftQualityNeedsEnoughShifts() {
        val few = Shifting.health(Totals(rearShifts = 20, takenBackUp = 10), null)
        assertEquals(false, few.enough)
        assertEquals(false, few.worthALook) // never judge on twenty shifts

        val clean = Shifting.health(Totals(rearShifts = 400, takenBackUp = 2, takenBackDown = 1), null)
        assertTrue(clean.clean)
        assertEquals(3L, clean.takenBack)

        val off = Shifting.health(Totals(rearShifts = 200, takenBackUp = 4, takenBackDown = 16), null)
        assertTrue(off.worthALook)
        assertEquals(0.1f, off.share, 1e-6f)
        assertEquals(false, off.towardsSmallCogs) // the large cogs are where it struggles

        // the ride in progress counts too
        val live = Shifting.health(Totals(rearShifts = 50), RideNow(0, 20, 0, 0, emptyMap(), takenBackUp = 5))
        assertEquals(70L, live.rearShifts)
        assertTrue(live.worthALook)
    }

    @Test fun crossChainingAndFavourite() {
        val u = mapOf(Gear(2, 1) to 10L, Gear(2, 6) to 70L, Gear(1, 12) to 20L)
        assertEquals(0.3f, Usage.crossShare(u, 2, 12), 1e-6f)
        assertEquals(0f, Usage.crossShare(u, 1, 12), 1e-6f)
        assertEquals(Gear(2, 6), Usage.favourite(u))
        assertEquals(0.7f, Usage.shares(u)[Gear(2, 6)]!!, 1e-6f)
    }
}
