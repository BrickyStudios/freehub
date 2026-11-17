package io.github.brickystudios.freehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // 105 Di2: 50/34 crank (pattern 6), 11-34 cassette (pattern 18), synchro S1, big ring, 7th of 12
    private val r7100 = bytes(0, 5, 255, 2, 2, 7, 12, 0, 0, 0, -2, -8, 8, 6, 0, 18, 0)

    @Test fun readsGears() {
        val g = Sbi.periodic(r7100)!!
        assertEquals(ShiftMode.SYNCHRO_S1, g.mode)
        assertNull(g.multiShiftInterval)
        assertEquals(2, g.front)
        assertEquals(7, g.rear)
        assertEquals(12, g.rearMax)
        assertEquals(-2, g.rearAdjust)
        assertEquals(-8, g.rearAdjustMin)
        assertEquals(50, g.frontTeeth)
        assertEquals(17, g.rearTeeth)
    }

    @Test fun highestPositionIsHardestGear() {
        val top = Sbi.periodic(r7100.copyOf().also { it[5] = 12 })!!
        val low = Sbi.periodic(r7100.copyOf().also { it[5] = 1; it[3] = 1 })!!
        assertEquals(11, top.rearTeeth)
        assertEquals(34, low.rearTeeth)
        assertEquals(34, low.frontTeeth)
    }

    @Test fun unknownValuesStayNull() {
        val g = Sbi.periodic(r7100.copyOf().also { it[5] = 255.toByte(); it[10] = (-128).toByte(); it[15] = 99 })!!
        assertNull(g.rear)
        assertNull(g.rearAdjust)
        assertNull(g.sprockets)
        assertNull(g.rearTeeth)
    }

    @Test fun ignoresOtherRecordsAndShortPayloads() {
        assertNull(Sbi.periodic(bytes(1, 2, 3)))
        assertNull(Sbi.periodic(bytes(7, 0, 0, 255)))
        assertNull(Sbi.periodic(r7100.copyOf(12)))
    }

    @Test fun teethNeedAMatchingPattern() {
        assertNull(Teeth.at(listOf(50, 34), 1, 3))
        assertNull(Teeth.at(listOf(50, 34), 3, 2))
        assertEquals(34, Teeth.at(listOf(50, 34), 1, 2))
    }

    @Test fun readsButtons() {
        val i = Sbi.instant(bytes(0x23, 0x10, 0xF0, 0x40, 0xF0)) as Instant.Buttons
        assertEquals(3, i.seq)
        assertEquals(mapOf(1 to Press.SHORT, 3 to Press.DOUBLE), i.channels)
    }

    @Test fun readsAlerts() {
        assertEquals(Instant.Alert(3), Sbi.instant(bytes(0x15, 3)))
        assertNull(Sbi.instant(bytes(0x35, 3)))
    }

    @Test fun pickedTeethWinAndFillUnknownPatterns() {
        // a derailleur that reports pattern numbers we do not know: no teeth
        val unknown = Sbi.periodic(bytes(0, 0, 255, 2, 2, 7, 12, 0, 0, 0, 0, 0xf8, 8, 0x63, 0, 0x63, 0))!!
        assertEquals(null, unknown.frontTeeth)
        val picked = unknown.withPicked(6, 20) // 50/34, 11-36
        assertEquals(50, picked.frontTeeth)
        assertEquals(17, picked.rearTeeth) // 11-12-13-14-15-[17]-19…: 7th of 12 from the hard end
        // nothing picked: the report stays as it was
        assertEquals(unknown, unknown.withPicked(-1, -1))
        assertTrue(20 in Teeth.cogPatterns(12) && 18 in Teeth.cogPatterns(12) && 2 !in Teeth.cogPatterns(12))
        assertTrue(6 in Teeth.ringPatterns(2) && 3 !in Teeth.ringPatterns(2))
    }
}
