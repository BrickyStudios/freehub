package io.github.brickystudios.freehub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsTest {
    // 0.0001° of latitude ≈ 11.1 m
    private val step = 0.0001

    @Test fun countsMovementIgnoresJitterBadFixesAndJumps() {
        val t = Track()
        assertEquals(0.0, t.add(50.0, 7.0, 5f, 0), 0.0) // first fix only anchors
        assertEquals(0.0, t.add(50.00003, 7.0, 5f, 2_000), 0.0) // 3 m: standing still, jitter
        assertEquals(11.1, t.add(50.0 + step, 7.0, 5f, 4_000), 0.1) // moved for real, from the anchor
        assertEquals(0.0, t.add(50.0 + 2 * step, 7.0, 40f, 6_000), 0.0) // 40 m accuracy: ignored
        assertEquals(11.1, t.add(50.0 + 2 * step, 7.0, 5f, 6_000), 0.1)
        assertEquals(0.0, t.add(50.1, 7.0, 5f, 8_000), 0.0) // 11 km in 2 s: a bad fix, start over there
        assertEquals(11.1, t.add(50.1 + step, 7.0, 5f, 10_000), 0.1)
    }

    @Test fun haversineMatchesAKnownDistance() {
        // one degree of latitude ≈ 111.2 km
        assertEquals(111_195.0, Track.metres(50.0, 7.0, 51.0, 7.0), 50.0)
    }

    @Test fun distanceSurvivesTheRoundTripAndAddsUp() {
        val r = Ride(0).apply { onGear(Gear(2, 6), 0); onGear(Gear(2, 8), 61_000); tick(121_000); meters = 1_234.5 }
        assertEquals(1_234.5, Ride.fromJson(JSONObject(r.toJson().toString())).meters, 0.0)
        val t = Totals().plus(r).plus(r)
        assertEquals(2_469.0, Totals.fromJson(JSONObject(t.toJson().toString())).meters, 0.0)
        // a file from before 0.5 has no distance at all
        assertEquals(0.0, Totals.fromJson(JSONObject("""{"rear":3,"front":0}""")).meters, 0.0)
    }
}
