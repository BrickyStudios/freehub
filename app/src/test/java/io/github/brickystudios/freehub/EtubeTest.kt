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

    @Test fun gearUsageRequestAndReplyClassification() {
        assertEquals("00 13 33 04 00 01 01", Etube.GEAR_USAGE.hex())
        // the RD-R7150's "nothing stored" reply: opcode 0x33, group 0x33, reply command 0x07
        assertTrue(Etube.isNoGearUsage(Etube.hex("3333073b00b9c1fdc277")))
        assertFalse(Etube.isGearUsageData(Etube.hex("3333073b00b9c1fdc277")))
        // a reply carrying data would be reply command 0x06
        assertTrue(Etube.isGearUsageData(Etube.hex("33330600000000")))
        assertFalse(Etube.isNoGearUsage(Etube.hex("33330600000000")))
        // the plain status packet (byte 1 is 0x01, not the group) is neither
        assertFalse(Etube.isNoGearUsage(Etube.hex("3301073b00")))
        assertFalse(Etube.isGearUsageData(Etube.hex("3301073b00")))
    }

    @Test fun errorLogRequestPerIndex() {
        // slot 0x00, opcode 0x13, group 0x32, command 0xCC (PCA_HIST_ERR_GET), then the entry index
        assertEquals("00 13 32 cc 00", Etube.errorLog(0).hex())
        assertEquals("00 13 32 cc 05", Etube.errorLog(5).hex())
    }

    @Test fun characteristicShortNames() {
        assertEquals("2AC1", DevLog.short(Sbi.PERIODIC))
        assertEquals("2A19", DevLog.short(Sbi.BATTERY_LEVEL))
    }
}
