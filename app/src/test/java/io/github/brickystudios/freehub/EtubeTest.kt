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

    @Test fun storedShiftModeUsesTheLiveCodes() {
        // capture 14: the same derailleur read in manual, then after two presses on its button in S2
        assertEquals(0, Etube.parseConfig(listOf(Etube.hex("000b00000cffff00ffff"))).shiftMode)
        assertEquals(ShiftMode.SYNCHRO_S2.code, Etube.parseConfig(listOf(Etube.hex("000b000600ffff00ffff"))).shiftMode)
        // a config saved before 0.8 only knew manual or not
        assertEquals(0, Etube.Config.fromJson(org.json.JSONObject("""{"manual":true}""")).shiftMode)
        assertEquals(null, Etube.Config.fromJson(org.json.JSONObject("""{"manual":false}""")).shiftMode)
    }

    @Test fun parseConfigDecodesRealRecords() {
        // real 2AFD records captured from the bike
        val recs = listOf("000b18010cffff", "000a180102ffff", "000b00000cffff", "000b080affffff", "00261064000000")
            .map { Etube.hex(it) }
        val c = Etube.parseConfig(recs)
        assertEquals(1 to 12, c.rearGears)
        assertEquals(1 to 2, c.frontGears)
        assertEquals(0, c.shiftMode)
        assertEquals(10, c.currentRearGear)
        assertEquals(100, c.batteryPercent)
        assertTrue(Etube.parseConfig(listOf(Etube.hex("000ab0ffffffff"))).isEmpty) // unprovisioned teeth → nothing

        // records from a bike that WAS set up in E-TUBE: its stored patterns are the parts on it, 50/34 and 11-34
        val setUp = Etube.parseConfig(
            listOf("000ab00600ffff", "000bb01200ffff", "000a080102ffff", "0026404600ffff")
                .map { Etube.hex(it) },
        )
        assertEquals(6, setUp.ringPattern)
        assertEquals(listOf(50, 34), setUp.chainrings)
        assertEquals(18, setUp.cogPattern)
        assertEquals(34, setUp.sprockets?.last())
        assertEquals(1, setUp.currentFrontGear)
        assertEquals(70, setUp.capacityPercent)

        val back = Etube.Config.fromJson(org.json.JSONObject(setUp.toJson().toString()))
        assertEquals(setUp, back)
        assertEquals(Etube.Config(), Etube.Config.fromJson(org.json.JSONObject(Etube.Config().toJson().toString())))
    }

    @Test fun aPartialReadKeepsWhatAnEarlierOneKnew() {
        // capture 12 (0.7.0, the never-set-up RD-R7150): whole 10-byte frames as they arrive, stale tails included.
        // This read brought no 0b·18 / 0b·00, the error log answered "empty" on 2AF9.
        val read12 = listOf(
            "000b7c01ffffffb7f86f", "000a08ffffffffb7f86f", "000b080affffffb7f86f", "000a080202ffff00ffff",
            "000a180102ffff00ffff", "000ab0ffffffff00ffff", "0026405affffff00ffff", "00261064ffffff00ffff",
            "33012e430000ff7dc277", "3332af3b0000ff7dc277", "3332cf420000ff7dc277",
        ).map { Etube.hex(it) }
        val now = Etube.parseConfig(read12)
        assertEquals(null, now.rearGears)
        assertEquals(1 to 2, now.frontGears)
        assertEquals(2, now.currentFrontGear)
        assertEquals(10, now.currentRearGear)
        assertEquals(90, now.capacityPercent)
        assertEquals(false, now.errorsStored)
        assertEquals(null, now.chainrings)

        // an earlier, fuller read (capture 11) still supplies the rear range and the shift mode
        val read11 = Etube.parseConfig(listOf("000b18010cfffff7f86f", "000b00000cfffff7f86f", "00264064000000f7f86f").map { Etube.hex(it) })
        val kept = now.over(read11)
        assertEquals(1 to 12, kept.rearGears)
        assertEquals(0, kept.shiftMode)
        assertEquals(90, kept.capacityPercent) // the newer reading wins where both have one
        assertEquals(kept, Etube.Config.fromJson(org.json.JSONObject(kept.toJson().toString())))

        // a derailleur with entries in its error log sends them as 32·ce (seen in the E-TUBE snoop)
        assertEquals(true, Etube.parseConfig(listOf(Etube.hex("0032ce00000101"))).errorsStored)
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
