package io.github.brickystudios.freehub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtubeTest {
    private val nonce = ByteArray(16) { it.toByte() }

    // expected values computed independently with `openssl enc -aes-128-ecb -nopad`
    @Test fun loginAnswersMatchOpenssl() {
        assertArrayEquals(Etube.hex("01" + "3a72c6d6f29630be3ec9dd85f5bd6be3"), Etube.passkeyAnswer("000000", nonce))
        assertArrayEquals(Etube.hex("02" + "e1b6c78c19964a801c0bbf1c6cc7cea8"), Etube.appAnswer(nonce))
    }

    @Test fun passkeyIsSixDigitsOrTheDefault() {
        assertEquals("123456", Etube.passkeyOrDefault("123456".toByteArray()))
        assertEquals("000000", Etube.passkeyOrDefault(null))
        assertEquals("000000", Etube.passkeyOrDefault(byteArrayOf(0, 0, 0)))
        assertEquals("000000", Etube.passkeyOrDefault("12345a".toByteArray()))
    }

    @Test fun unitCommandWrapsSlotGroupCommandAndParams() {
        // slot, opcode 0x13, then group, command, params
        assertEquals("00 13 33 04 00 01 01", Etube.unitCommand(0, 0x33, 0x04, 0, 1, 1).hex())
        assertEquals("02 13 32 ac 00 00 00 00", Etube.unitCommand(2, 0x32, 0xAC, 0, 0, 0, 0).hex())
        assertEquals("05 13 01 2c 00", Etube.unitCommand(5, 0x01, 0x2C, 0).hex())
    }

    @Test fun replyDataVsNoData() {
        // fw-version reply command 0x2e = 0x2c | 2 -> data
        assertEquals(true, Etube.replyHasData(Etube.hex("33012e430000fffdc277")))
        // gear-usage reply command 0x07 = 0x04 | 3 -> no data
        assertEquals(false, Etube.replyHasData(Etube.hex("3333073b000000fdc277")))
        // not a 0x33 unit-command reply
        assertEquals(null, Etube.replyHasData(Etube.hex("100201")))
    }

    @Test fun setShiftModeCommandIsADcasFrame() {
        // EXTM_RA_SFT_MODE_SEL_SET_C: group 0x0B, command 0xF8, 1 = manual / 0 = synchronized, trailing 0, DCAS-framed
        assertEquals("bb 48 00 0b f8 01 00 4d bb", Etube.setShiftMode(true).hex())
        assertEquals("bb 48 00 0b f8 00 00 4c bb", Etube.setShiftMode(false).hex())
    }

    @Test fun dcasFrameMatchesReconstruction() {
        // unit command: flag 0xBB, [control 0x48, slot, group, cmd, params, FCS=(ctrl+Σdata+1)&0xFF], flag 0xBB
        assertEquals("bb 48 00 01 2c 00 76 bb", Etube.dcasCommand(0, 0x01, 0x2C, 0).hex())
        assertEquals("bb 48 00 32 ac 10 01 00 00 38 bb", Etube.dcasCommand(0, 0x32, 0xAC, 0x10, 1, 0, 0).hex())
        // bus-setup frames (controlInfo + data), byte-exact from the decompiled bytecode:
        assertEquals("bb 03 60 64 bb", Etube.dcasFrame(0x03, 0x60).hex()) // setProtocolMode 0x40|0x20
        assertEquals("bb 04 05 bb", Etube.dcasFrame(0x04).hex())          // checkProtocolMode (no data)
        assertEquals("bb 06 1f 26 bb", Etube.dcasFrame(0x06, 0x1F).hex()) // setTargetAddress 0x1F
        assertEquals("bb 06 00 07 bb", Etube.dcasFrame(0x06, 0x00).hex()) // setTargetAddress slot 0
    }

    @Test fun probeSweepHasDistinctLabels() {
        val reads = Etube.PROBE_READS
        assertTrue(reads.size >= 5)
        assertEquals(reads.size, reads.map { it.first }.toSet().size) // labels are unique
    }

    @Test fun characteristicShortNames() {
        assertEquals("2AC1", DevLog.short(Sbi.PERIODIC))
        assertEquals("2A19", DevLog.short(Sbi.BATTERY_LEVEL))
    }
}
