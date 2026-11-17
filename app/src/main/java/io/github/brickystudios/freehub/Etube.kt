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
     * A unit command in the form the RD-R7150 accepts (BikeBridge's unit-command opcode 0x13): slot 0x00,
     * opcode 0x13, group, command, then the parameters. The reply on 0x2AF9 is `33 <group> <reply command> …`,
     * where the reply command is `command | 2` for data and `command | 3` when the unit has nothing to send.
     * The exact group/command bytes come from E-TUBE's own `UnitCommandSetting(…, group, command, …)` sites.
     */
    fun unitCommand(group: Int, command: Int, vararg params: Int): ByteArray =
        byteArrayOf(0x00, 0x13, group.toByte(), command.toByte()) + ByteArray(params.size) { params[it].toByte() }

    /**
     * Set the shift mode (E-TUBE's `DCC_CMD_EXTM_RA_SFT_MODE_SEL_SET_C`): group 0x0B, command 0xF8, then
     * `1` = manual front shifting, `0` = synchronized, and a trailing 0. A write — it changes the derailleur.
     */
    fun setShiftMode(manual: Boolean) = unitCommand(0x0B, 0xF8, if (manual) 1 else 0, 0)

    /**
     * Every read the developer "probe" sweeps after login, so one capture reveals every reply format at once.
     * All read-only (`*_GET`); the parameters are a best guess (mostly a zero), which is enough to see which
     * commands answer and how. Decoding and the matching screens follow from the capture.
     */
    val PROBE_READS: List<Pair<String, ByteArray>> = listOf(
        "fw-version" to unitCommand(0x01, 0x2C, 0),        // UNIT_FW_VER_GET
        "unit-count" to unitCommand(0x01, 0x94, 0),        // SYS_HAVE_UNIT_NO_GET_C
        "slot-no" to unitCommand(0x02, 0x14, 0),           // SYS_SLOT_STOCK_NO_GET
        "date" to unitCommand(0x4A, 0x04, 0),              // TMR_DATE_GET
        "date-adj" to unitCommand(0x4A, 0x0C, 0),          // TMR_DATE_ADJ_GET
        "value" to unitCommand(0x32, 0x84, 0),             // PCA_DATA_VALUE_PART_UNIT_GET
        "data-via-unit" to unitCommand(0x32, 0xBC, 0),     // PCA_DATA_VIA_UNIT_GET
        "synchro" to unitCommand(0x32, 0xAC, 0, 0, 0, 0),  // PCA_SYNC_GET_C (shift-mode / synchro config)
        "sub-synchro" to unitCommand(0x32, 0xEC, 0, 0, 0, 0), // PCA_SUB_SYNC_GET_C (semi-synchro)
        "auto-shift" to unitCommand(0x0B, 0xEC, 0),        // EXTM_RA_AUTO_SFT_GET_C
        "alt-num" to unitCommand(0x0B, 0x7C, 0),           // EXTM_RA_ALT_NUM_GET
        "usage-old" to unitCommand(0x32, 0xC4, 0),         // PCA_HIST_USE_GET
        "tampering" to unitCommand(0x33, 0x64, 0),         // PCA2_TAMPERING_INFO_GET_C
        "gear-usage" to unitCommand(0x33, 0x04, 0, 1, 1),  // PCA2_HIST2_USE_GET (known: no data on this RD)
        "error-log0" to unitCommand(0x32, 0xCC, 0),        // PCA_HIST_ERR_GET index 0
        "error-log1" to unitCommand(0x32, 0xCC, 1),
        "error-log2" to unitCommand(0x32, 0xCC, 2),
    )

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
