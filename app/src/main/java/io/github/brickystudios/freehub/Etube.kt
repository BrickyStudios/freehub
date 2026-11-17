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
     * The gear-usage read the RD-R7150 accepts (BikeBridge's unit-command opcode 0x13): slot 0x00, opcode 0x13,
     * group 0x33, command 0x04, log 0, sequence 1, transfer mode 1. The reply on 0x2AF9 is `33 33 <command> …`:
     * opcode 0x33, group 0x33, then the reply command. A successful read answers command 0x06 (`0x04 | 2`); the
     * tested derailleur answers 0x07 instead — the no-data code — because it was never set up in E-TUBE and holds
     * no history. The DCAS `0x48` form of the same command drew no reply on this unit.
     */
    val GEAR_USAGE = hex("00133304000101")

    private const val REPLY_OPCODE = 0x33
    private const val REPLY_GROUP = 0x33
    const val REPLY_OK = 0x06      // command 0x04 answered with data
    const val REPLY_NO_DATA = 0x07 // command 0x04 answered "nothing stored"

    private fun replyCommand(v: ByteArray): Int? =
        if (v.getOrNull(0)?.toInt()?.and(0xFF) == REPLY_OPCODE && v.getOrNull(1)?.toInt()?.and(0xFF) == REPLY_GROUP)
            v.getOrNull(2)?.toInt()?.and(0xFF) else null

    /** a 0x2AF9 reply that carries gear-usage data (reply command 0x06) */
    fun isGearUsageData(v: ByteArray) = replyCommand(v) == REPLY_OK

    /** the derailleur's "no gear usage stored" reply (reply command 0x07) */
    fun isNoGearUsage(v: ByteArray) = replyCommand(v) == REPLY_NO_DATA

    /** how many error-log entries to read (E-TUBE's `PCA_HIST_ERR_GET` is one index per request) */
    const val ERROR_LOG_COUNT = 8

    /**
     * One entry of the derailleur's error history (E-TUBE's `PCA_HIST_ERR_GET`): slot 0x00, opcode 0x13,
     * group 0x32, command 0xCC, then the entry index. Read-only, read one index at a time. Errors are logged
     * whatever the drivetrain config, so unlike gear usage this should hold data. The reply format is read off
     * the first real capture before it is parsed.
     */
    fun errorLog(index: Int) = hex("001332cc") + byteArrayOf(index.toByte())

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
