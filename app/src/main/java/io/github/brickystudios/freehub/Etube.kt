package io.github.brickystudios.freehub

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The E-TUBE service (0x18FF), where Shimano's own app reads the derailleur's memory. Pure, so the JVM tests cover it.
 *
 * Login: read a 16-byte nonce, answer with it AES-encrypted under a key built from the passkey, then again under
 * an app key. Keys and handshake as in BikeBridge (codeberg.org/bg443/BikeBridge, GPL-3.0), which does this for
 * Shimano e-bike motors. The gear usage request is the one E-TUBE PROJECT sends (group 0x33, command 0x04).
 */
object Etube {
    val SERVICE: UUID = Sbi.shimano(0x18FF)
    val AUTH_CONTROL: UUID = Sbi.shimano(0x2AF3)
    val NONCE: UUID = Sbi.shimano(0x2AF4)
    val PASSKEY: UUID = Sbi.shimano(0x2AF8)
    val PCE_RESPONSE: UUID = Sbi.shimano(0x2AF9)
    val PCE_COMMAND: UUID = Sbi.shimano(0x2AFA)
    val FREE_RESPONSE: UUID = Sbi.shimano(0x2AFD)
    val FEATURE: UUID = Sbi.shimano(0x2AFF)
    val AF5: UUID = Sbi.shimano(0x2AF5) // E-TUBE writes 01 then 00 here during the session opener

    /** what a derailleur never set up in E-TUBE uses */
    const val DEFAULT_PASSKEY = "000000"

    private val PASSKEY_KEY_HEAD = hex("00011147a53fa2795248") // + six passkey digits = 16 bytes
    private val APP_KEY = hex("0002d39ca426c694e30bc922e87bb58d")

    val USER_OK = hex("100101")
    val USER_WRONG = hex("100102")
    val APP_OK = hex("100201")
    /** after login: E-TUBE (and BikeBridge) switch the unit to command mode; live gear reports pause until reconnect */
    val COMMAND_MODE = hex("ff00")

    /**
     * A BLE write to 0x2AFA. Byte 0 is the BLE sequence header (0x00 for one block ≤ 18 bytes), then the transport
     * control byte, then the payload. The reply on 0x2AF9 leads with `controlInfo | 0x20` (0x13→0x33, 0x03→0x23,
     * 0x0C→0x2C). This is E-TUBE's real BLE framing — the 0xBB/0x48 DCAS frame is the internal serial transport and
     * is never written over BLE (it drew total silence when tried).
     */
    fun ble(controlInfo: Int, vararg payload: Int): ByteArray =
        byteArrayOf(0x00, controlInfo.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    /** A unit command: control 0x13, then group, command, params. Reply `33 <group> <command|2 = data / |3 = NAK>`. */
    fun unitCommand(group: Int, command: Int, vararg params: Int): ByteArray = ble(0x13, group, command, *params)

    /** Set the shift mode (group 0x0B, command 0xF8): 1 = manual front shifting, 0 = synchronized. A write. */
    fun setShiftMode(manual: Boolean) = unitCommand(0x0B, 0xF8, if (manual) 1 else 0, 0)

    /**
     * The exact session E-TUBE runs after login, read byte-for-byte from an E-TUBE Bluetooth HCI snoop: command
     * mode, a `00 05` on 2AFA, an id read, two writes on 2AF5, a firmware read, then `setProtocolMode` (`00 03 00`).
     * The last one makes the derailleur **stream its whole configuration, unsolicited, on 0x2AFD** as
     * `00 <group> <command> <data…>` — no per-feature reads are needed. Each step is `(label, characteristic, bytes)`.
     */
    val SESSION_FLOW: List<Triple<String, UUID, ByteArray>> = listOf(
        Triple("command-mode", FEATURE, COMMAND_MODE),         // ff 00 → 2AFF
        Triple("pce-05", PCE_COMMAND, ble(0x05)),              // 00 05 → 2AFA, reply 25 44 …
        Triple("read id", PCE_COMMAND, unitCommand(0x01, 0x1C, 0)),
        Triple("af5 on", AF5, byteArrayOf(0x01)),
        Triple("af5 off", AF5, byteArrayOf(0x00)),
        Triple("read firmware", PCE_COMMAND, unitCommand(0x01, 0x2C, 0)),
        Triple("setProtocolMode", PCE_COMMAND, ble(0x03, 0x00)), // 00 03 00 → config streams on 2AFD
        // the basic config auto-streams on 2AFD, but the synchronised-shift detail (group 0x32 PCA) does not —
        // E-TUBE reads it separately. With the session now open these should answer with data (33 32 ae …) on
        // 2AF9 instead of the NAK (33 32 af …) they gave before.
        Triple("synchro", PCE_COMMAND, unitCommand(0x32, 0xAC, 0x10, 1, 0, 0)),
        Triple("sub-synchro", PCE_COMMAND, unitCommand(0x32, 0xEC, 0x10, 1, 0, 0)),
        Triple("connecting-system", PCE_COMMAND, unitCommand(0x33, 0x4C, 0)),
        Triple("error-log", PCE_COMMAND, unitCommand(0x32, 0xCC, 0)),
    )

    /** What the 2AFD config stream tells us about the derailleur. null fields = the unit did not report them. */
    data class Config(
        val rearGears: Pair<Int, Int>? = null,  // min..max, e.g. 1..12
        val frontGears: Pair<Int, Int>? = null,  // 1..2 on a double
        val shiftModeManual: Boolean? = null,    // true = manual front shift, false = synchronized
        val currentRearGear: Int? = null,
        val currentFrontGear: Int? = null,
        val batteryPercent: Int? = null,
        /** what is left of the charge, as the unit reports it; a coarser figure than the battery percent */
        val capacityPercent: Int? = null,
        /** the pattern numbers stored in the unit; null = never set up in E-TUBE, which is why the teeth stay empty */
        val ringPattern: Int? = null,
        val cogPattern: Int? = null,
        /** the derailleur's error log: true = it holds entries, false = empty */
        val errorsStored: Boolean? = null,
    ) {
        val chainrings get() = ringPattern?.let(Teeth::chainrings)
        val sprockets get() = cogPattern?.let(Teeth::sprockets)
        val isEmpty get() = this == Config()

        /** A read does not always bring every record, so where this one is silent, keep what an earlier one knew. */
        fun over(old: Config?) = if (old == null) this else Config(
            rearGears = rearGears ?: old.rearGears, frontGears = frontGears ?: old.frontGears,
            shiftModeManual = shiftModeManual ?: old.shiftModeManual,
            currentRearGear = currentRearGear ?: old.currentRearGear, currentFrontGear = currentFrontGear ?: old.currentFrontGear,
            batteryPercent = batteryPercent ?: old.batteryPercent, capacityPercent = capacityPercent ?: old.capacityPercent,
            ringPattern = ringPattern ?: old.ringPattern, cogPattern = cogPattern ?: old.cogPattern,
            errorsStored = errorsStored ?: old.errorsStored,
        )

        fun toJson(): org.json.JSONObject = org.json.JSONObject()
            .put("rear", rearGears?.second).put("rearMin", rearGears?.first)
            .put("front", frontGears?.second).put("frontMin", frontGears?.first)
            .put("manual", shiftModeManual).put("gear", currentRearGear).put("ring", currentFrontGear)
            .put("battery", batteryPercent).put("capacity", capacityPercent)
            .put("ringPattern", ringPattern).put("cogPattern", cogPattern).put("errors", errorsStored)

        companion object {
            fun fromJson(o: org.json.JSONObject): Config {
                fun int(k: String) = if (o.isNull(k)) null else o.optInt(k)
                fun pair(min: String, max: String) = int(max)?.let { (int(min) ?: 1) to it }
                return Config(
                    rearGears = pair("rearMin", "rear"),
                    frontGears = pair("frontMin", "front"),
                    shiftModeManual = if (o.isNull("manual")) null else o.optBoolean("manual"),
                    currentRearGear = int("gear"), currentFrontGear = int("ring"),
                    batteryPercent = int("battery"), capacityPercent = int("capacity"),
                    ringPattern = int("ringPattern"), cogPattern = int("cogPattern"),
                    errorsStored = if (o.isNull("errors")) null else o.optBoolean("errors"),
                )
            }
        }
    }

    /**
     * Decode the records the derailleur streams on 0x2AFD after the session opens. Each is `00 <group> <command>
     * <params…>` (then a 3-byte session tag). The (group, command) → meaning map and byte layout are from E-TUBE's
     * own code; group `0x0A` is the front derailleur, `0x0B` the rear, `0x26` the battery. Checked against captures
     * from two real 2×12 bikes — one never set up in E-TUBE, one set up, whose stored patterns decode to the
     * crankset and cassette actually on it. `0xFF` means "not stored".
     *
     * Unit-command replies on 0x2AF9 (`33 <group> <command> …`) carry group and command at the same places, so
     * they go in too: the error log answers `32·ce` with entries and `32·cf` when it is empty.
     */
    fun parseConfig(records: List<ByteArray>): Config {
        var c = Config()
        fun param(v: ByteArray, i: Int) = v.getOrNull(3 + i)?.toInt()?.and(0xFF)?.takeIf { it != 0xFF }
        // pattern numbers come as two bytes; every capture so far has the high byte at 0, as 0x2AC1 sends them
        fun pattern(v: ByteArray) = param(v, 0)?.let { it or ((param(v, 1) ?: 0) shl 8) }
        for (v in records) {
            val group = v.getOrNull(1)?.toInt()?.and(0xFF) ?: continue
            val cmd = v.getOrNull(2)?.toInt()?.and(0xFF) ?: continue
            when (group shl 8 or cmd) {
                0x0B18 -> { val lo = param(v, 0); val hi = param(v, 1); if (lo != null && hi != null && hi in 1..24) c = c.copy(rearGears = lo to hi) }
                0x0A18 -> { val lo = param(v, 0); val hi = param(v, 1); if (lo != null && hi != null && hi in 1..4) c = c.copy(frontGears = lo to hi) }
                0x0B00 -> param(v, 0)?.let { c = c.copy(shiftModeManual = it == 0) }
                0x0B08 -> param(v, 0)?.let { if (it in 1..24) c = c.copy(currentRearGear = it) }
                0x0A08 -> param(v, 0)?.let { if (it in 1..4) c = c.copy(currentFrontGear = it) }
                0x2610 -> param(v, 0)?.let { if (it in 0..100) c = c.copy(batteryPercent = it) }
                0x2640 -> param(v, 0)?.let { if (it in 0..100) c = c.copy(capacityPercent = it) }
                0x0AB0 -> pattern(v)?.let { if (Teeth.chainrings(it) != null) c = c.copy(ringPattern = it) }
                0x0BB0 -> pattern(v)?.let { if (Teeth.sprockets(it) != null) c = c.copy(cogPattern = it) }
                0x32CE -> c = c.copy(errorsStored = true)
                0x32CF -> if (c.errorsStored == null) c = c.copy(errorsStored = false)
            }
        }
        return c
    }

    /**
     * Whether a `33 <group> <reply command> …` reply carries data. The reply command is the request command
     * with bit 1 set for data (`& 3 == 2`) or bits 0+1 set when the unit has nothing to send (`& 3 == 3`).
     * null when it is not a unit-command reply.
     */
    fun replyHasData(v: ByteArray): Boolean? {
        if (v.getOrNull(0)?.toInt()?.and(0xFF) != 0x33) return null
        return when ((v.getOrNull(2)?.toInt()?.and(0xFF) ?: return null) and 3) {
            2 -> true
            3 -> false
            else -> null
        }
    }

    /** Passkeys are six digits; anything else read from the unit is not one. */
    fun passkeyOrDefault(raw: ByteArray?): String =
        raw?.decodeToString()?.trim()?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: DEFAULT_PASSKEY

    fun passkeyAnswer(passkey: String, nonce: ByteArray) = byteArrayOf(1) + aes(PASSKEY_KEY_HEAD + passkey.toByteArray(), nonce)

    fun appAnswer(nonce: ByteArray) = byteArrayOf(2) + aes(APP_KEY, nonce)

    private fun aes(key: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block).copyOf(16)
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

/**
 * Developer options: every packet to and from the derailleur, with the time, so a rider can send a capture
 * instead of setting up Android's HCI log. Off unless switched on under Bike; kept in memory only.
 */
object DevLog {
    private const val MAX = 5_000
    @Volatile var on = false
    private val lines = ArrayDeque<String>()
    /** for the screen: how many lines there are */
    val size = MutableStateFlow(0)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** 0x2AC1 for both Shimano's and the Bluetooth SIG's base UUID: the 16 bits sit in the top half */
    fun short(u: UUID?) = u?.let { "%04X".format((it.mostSignificantBits ushr 32).toInt() and 0xFFFF) } ?: "----"

    fun add(kind: String, uuid: UUID?, bytes: ByteArray? = null, note: String = "") {
        if (!on) return
        val line = "${time.format(Date())} $kind ${short(uuid)}" + (bytes?.let { " " + it.hex() } ?: "") + (if (note.isNotEmpty()) "  # $note" else "")
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            size.value = lines.size
        }
    }

    fun note(text: String) = add("--", null, null, text)

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear(); size.value = 0 }
}
