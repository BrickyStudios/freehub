package io.github.brickystudios.freehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTest {
    @Test fun photoShrinksToAboutSixteenHundredPixels() {
        assertEquals(1, BikePhotos.sampleSize(1600))
        assertEquals(2, BikePhotos.sampleSize(4000))  // 12 MP phone photo → 2000 px
        assertEquals(4, BikePhotos.sampleSize(8000))  // 50 MP → 2000 px
    }

    @Test fun lowBatteryWarnsOncePerStep() {
        assertNull(LowBattery.due(25, 0))
        assertEquals(20, LowBattery.due(20, 0))
        assertNull(LowBattery.due(15, 20))      // already warned at 20
        assertEquals(10, LowBattery.due(9, 20))
        assertNull(LowBattery.due(5, 10))
        assertEquals(10, LowBattery.due(8, 0))  // first reading already low: the stricter step, once
    }

    @Test fun shimanoFigureUntilEnoughIsKnown() {
        assertNull(Range.learned(emptyList()))
        // 3 % used: too coarse to learn from
        val log = listOf(Charge(0, 90, 1_000.0), Charge(1, 87, 1_090.0))
        assertNull(Range.learned(log))
        assertEquals(820.0, Range.remainingKm(82, log), 0.001)
    }

    @Test fun learnsAcrossRidesWithoutDistanceAndSkipsCharges() {
        val log = listOf(
            Charge(0, 90, 1_000.0),
            Charge(1, 86, 1_000.0), // a ride without location access: no km for this drop
            Charge(2, 86, 1_080.0), // a ride measured by GPS
            Charge(3, 84, 1_080.0),
            Charge(4, 100, 1_150.0), // charged: this stretch says nothing about consumption
            Charge(5, 100, 1_150.0),
            Charge(6, 99, null), // no GPS metre counted yet
        )
        assertEquals(80.0 / 6, Range.learned(log)!!, 0.001)
        assertEquals(4L, Range.lastCharged(log))
    }

    @Test fun roundTrips() {
        val log = listOf(Charge(5, 77, null), Charge(9, 76, 12.5))
        assertEquals(log, Range.fromJson(Range.toJson(log)))
    }
}
