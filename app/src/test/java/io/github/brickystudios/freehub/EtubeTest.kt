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

    @Test fun gearUsageProbesAreTheCandidatesWeTry() {
        val probes = Etube.GEAR_USAGE_PROBES.toMap()
        assertEquals("00 13 33 04 00 01 01", probes["unit13"]!!.hex()) // BikeBridge write opcode 0x13
        assertEquals("00 33 04 00 01 01", probes["raw"]!!.hex())       // what Freehub sent until now
        // DCAS: control 0x48 + body + FCS; sum(48 00 33 04 00 01 01) = 0x81, +1 = 0x82
        assertEquals("48 00 33 04 00 01 01 82", probes["dcas48"]!!.hex())
    }

    @Test fun fcsIsLowByteOfSumPlusOne() {
        assertEquals(0x82.toByte(), Etube.fcs(Etube.hex("48003304000101")))
        assertEquals(0x01.toByte(), Etube.fcs(Etube.hex("")))   // empty → 0 + 1
        assertEquals(0x00.toByte(), Etube.fcs(Etube.hex("ff"))) // 255 + 1 wraps to 0
    }

    @Test fun characteristicShortNames() {
        assertEquals("2AC1", DevLog.short(Sbi.PERIODIC))
        assertEquals("2A19", DevLog.short(Sbi.BATTERY_LEVEL))
    }
}
