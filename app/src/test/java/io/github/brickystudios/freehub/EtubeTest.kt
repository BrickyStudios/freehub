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

    @Test fun unitCommandWrapsGroupCommandAndParams() {
        // slot 0x00, opcode 0x13, then group, command, params
        assertEquals("00 13 33 04 00 01 01", Etube.unitCommand(0x33, 0x04, 0, 1, 1).hex())
        assertEquals("00 13 32 cc 00", Etube.unitCommand(0x32, 0xCC, 0).hex())
        assertEquals("00 13 01 2c 00", Etube.unitCommand(0x01, 0x2C, 0).hex())
    }

    @Test fun setShiftModeCommand() {
        // EXTM_RA_SFT_MODE_SEL_SET_C: group 0x0B, command 0xF8, 1 = manual / 0 = synchronized, trailing 0
        assertEquals("00 13 0b f8 01 00", Etube.setShiftMode(true).hex())
        assertEquals("00 13 0b f8 00 00", Etube.setShiftMode(false).hex())
    }

    @Test fun probeSweepHasDistinctLabelsAndWellFormedCommands() {
        val reads = Etube.PROBE_READS
        assertTrue(reads.size >= 10)
        assertEquals(reads.size, reads.map { it.first }.toSet().size) // labels are unique
        reads.forEach { (_, bytes) ->
            assertEquals(0x00.toByte(), bytes[0])
            assertEquals(0x13.toByte(), bytes[1]) // every command uses the 0x13 unit-command opcode
        }
    }

    @Test fun characteristicShortNames() {
        assertEquals("2AC1", DevLog.short(Sbi.PERIODIC))
        assertEquals("2A19", DevLog.short(Sbi.BATTERY_LEVEL))
    }
}
