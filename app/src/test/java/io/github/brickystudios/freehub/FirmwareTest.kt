package io.github.brickystudios.freehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareTest {
    @Test
    fun oldDerailleurGetsEverythingSinceItsVersion() {
        val c = Firmware.check("RDR7150 123", "4.1.0.0")!!
        assertEquals("4.4.0", c.latest.version)
        assertEquals(listOf("4.2.1", "4.2.3", "4.3.0", "4.3.1", "4.4.0"), c.news.map { it.version })
        assertTrue(c.levers) // the radio fix in 4.2.3 needs the levers updated too
    }

    @Test
    fun upToDateMeansNoNews() {
        assertTrue(Firmware.check("RDR7150 123", "4.4.0.0")!!.news.isEmpty())
        assertTrue(Firmware.check("RDR7150 123", "4.5.0")!!.news.isEmpty())
    }

    @Test
    fun theOtherModelsAndTheirNumbering() {
        assertEquals(listOf("4.2.0", "4.2.3", "4.3.0", "4.3.1", "4.4.0"), Firmware.check("RDR9250 123", "4.1.1.0")!!.news.map { it.version })
        assertEquals(1, Firmware.check("RD-R8150", "4.3.1")!!.news.size)
        assertEquals(false, Firmware.check("RDRX825 123", "4.2.3")!!.levers)
    }

    @Test
    fun unknownModelOrVersionSaysNothing() {
        assertNull(Firmware.check("RDM9250 123", "4.1.0.0"))
        assertNull(Firmware.check(null, "4.1.0.0"))
        assertNull(Firmware.check("RDR7150 123", null))
        assertNull(Firmware.check("RDR7150 123", "v4.x"))
    }
}
