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

    @Test fun bleFramingAndUnitCommand() {
        // BLE: byte 0 = sequence header 0x00, then control, then payload
        assertEquals("00 13 33 04 00 01 01", Etube.unitCommand(0x33, 0x04, 0, 1, 1).hex()) // control 0x13 unit command
        assertEquals("00 13 32 ac 10 01 00 00", Etube.unitCommand(0x32, 0xAC, 0x10, 1, 0, 0).hex())
        assertEquals("00 03 00", Etube.ble(0x03, 0x00).hex())  // setProtocolMode(0,0)
        assertEquals("00 0c 01", Etube.ble(0x0C, 0x01).hex())  // setPCConnectMode(1)
    }

    @Test fun sessionFlowMatchesTheHciSnoop() {
        val steps = Etube.SESSION_FLOW.map { it.third.hex() }
        assertEquals("ff 00", steps[0])              // command mode
        assertEquals("00 05", steps[1])              // 2AFA 00 05
        assertEquals("00 13 01 1c 00", steps[2])     // id read
        assertEquals("01", steps[3]); assertEquals("00", steps[4]) // 2AF5 writes
        assertEquals("00 13 01 2c 00", steps[5])     // firmware read
        assertEquals("00 03 00", steps[6])           // setProtocolMode → config streams on 2AFD
    }

    @Test fun replyDataVsNoData() {
        // fw-version reply command 0x2e = 0x2c | 2 -> data
        assertEquals(true, Etube.replyHasData(Etube.hex("33012e430000fffdc277")))
        // gear-usage reply command 0x07 = 0x04 | 3 -> no data
        assertEquals(false, Etube.replyHasData(Etube.hex("3333073b000000fdc277")))
        // not a 0x33 unit-command reply
        assertEquals(null, Etube.replyHasData(Etube.hex("100201")))
    }

    @Test fun setShiftModeIsABleUnitCommand() {
        // group 0x0B, command 0xF8, 1 = manual / 0 = synchronized, trailing 0
        assertEquals("00 13 0b f8 01 00", Etube.setShiftMode(true).hex())
        assertEquals("00 13 0b f8 00 00", Etube.setShiftMode(false).hex())
    }

    @Test fun parseConfigDecodesRealRecords() {
        // real 2AFD records captured from the bike
        val recs = listOf("000b18010cffff", "000a180102ffff", "000b00000cffff", "000b080affffff", "00261064000000")
            .map { Etube.hex(it) }
        val c = Etube.parseConfig(recs)
        assertEquals(1 to 12, c.rearGears)
        assertEquals(1 to 2, c.frontGears)
        assertEquals(true, c.shiftModeManual)
        assertEquals(10, c.currentRearGear)
        assertEquals(100, c.batteryPercent)
        assertTrue(Etube.parseConfig(listOf(Etube.hex("000bb4ffffffff"))).isEmpty) // unprovisioned teeth → nothing
    }

    @Test fun sessionFlowHasDistinctLabels() {
        val steps = Etube.SESSION_FLOW
        assertTrue(steps.size >= 5)
        assertEquals(steps.size, steps.map { it.first }.toSet().size) // labels are unique
    }

    @Test fun characteristicShortNames() {
        assertEquals("2AC1", DevLog.short(Sbi.PERIODIC))
        assertEquals("2A19", DevLog.short(Sbi.BATTERY_LEVEL))
    }
}
