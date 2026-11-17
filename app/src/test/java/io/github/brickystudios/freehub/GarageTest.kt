package io.github.brickystudios.freehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarageTest {
    // two bikes of the same model: only the derailleur's address tells them apart
    private val mine = Profile("C1:00:00:00:00:01", "Mein Rad", "RDR7150 111", rings = 6, cogs = 18, wheel = 2155, raw = "0000")
    private val brothers = Profile("C1:00:00:00:00:02", "Rad 2", "RDR7150 222")

    @Test fun roundTripsBikesAndTheActiveOne() {
        val (bikes, active) = GarageFile.read(GarageFile.write(listOf(mine, brothers), brothers.address))
        assertEquals(listOf(mine, brothers), bikes)
        assertEquals(brothers.address, active)
    }

    @Test fun anUnknownOrMissingActiveFallsBackToTheFirstBike() {
        assertEquals(mine.address, GarageFile.read(GarageFile.write(listOf(mine), "gone")).second)
        assertEquals(mine.address, GarageFile.read(GarageFile.write(listOf(mine), null)).second)
        assertNull(GarageFile.read(GarageFile.write(emptyList(), null)).second)
    }

    @Test fun eachBikeGetsItsOwnFilesAndAFreeName() {
        assertEquals("c10000000001", mine.key)
        assertEquals("c10000000002", brothers.key)
        val nth = { n: Int -> "Rad $n" }
        assertEquals("Mein Rad", GarageFile.defaultName(emptyList(), "Mein Rad", nth))
        assertEquals("Rad 3", GarageFile.defaultName(listOf(mine, brothers), "Mein Rad", nth)) // "Rad 2" is taken
    }

    @Test fun missingFieldsTakeTheDefaults() {
        val p = Profile.fromJson(org.json.JSONObject("""{"address":"A","name":"X"}"""))
        assertEquals(Profile("A", "X"), p)
        assertEquals(-1, p.rings)
        assertEquals(2136, p.wheel)
    }

    @Test fun tellsTwoDerailleursOfTheSameModelApart() {
        assertEquals("111", unitNumber(mine.model))
        assertEquals("RD-R7150", model(mine.model))
        assertNull(unitNumber("RDR7150"))
    }
}
