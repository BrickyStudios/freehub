package io.github.brickystudios.freehub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test fun gearUsageRequestIsWhatEtubeSends() {
        // control byte, group 0x33, command 0x04, log 0, sequence 1, high-speed transfer
        assertEquals("00 33 04 00 01 01", Etube.GEAR_USAGE.hex())
        assertEquals("2AC1", DevLog.short(Sbi.PERIODIC))
        assertEquals("2A19", DevLog.short(Sbi.BATTERY_LEVEL))
    }
}
