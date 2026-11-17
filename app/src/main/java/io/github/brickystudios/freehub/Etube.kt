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

    /** group `0x33` echoed in byte 1 of a real gear-usage reply (`33 33 …`); `33 01 …` is only a status packet */
    const val GEAR_USAGE_REPLY_GROUP = 0x33

    /** the command bytes for the gear-usage read: slot 0x00, group 0x33, command 0x04, log 0, sequence 1, high speed */
    private val GEAR_USAGE_BODY = hex("003304000101")

    /** E-TUBE's frame check, `Common.createFcs`: low byte of (sum of the bytes + 1) */
    fun fcs(bytes: ByteArray): Byte = (bytes.sumOf { it.toInt() and 0xFF } + 1).toByte()

    /**
     * A DCAS frame as E-TUBE sends unit commands: control byte 0x48, the body, then the one-byte FCS at the
     * second-to-last position and an end byte last (the receiver checks the FCS over everything before it, so the
     * end byte is outside the FCS). The exact end byte is from `createFrame`, which did not decompile, so it is probed.
     */
    fun dcasFrame(body: ByteArray, end: Int): ByteArray {
        val head = byteArrayOf(0x48) + body
        return head + fcs(head) + end.toByte()
    }

    /**
     * Gear-usage request candidates for the dev read. `unit13` (BikeBridge's 0x13 opcode) is accepted but only
     * draws a single status record, no stream; the DCAS frames carry the E-TUBE control byte 0x48 and a frame
     * check, each with a different end byte, because an earlier DCAS attempt omitted the end byte (wrong FCS
     * position) and drew no reply at all. The capture shows whether a proper DCAS frame streams the history.
     */
    val GEAR_USAGE_PROBES: List<Pair<String, ByteArray>> = listOf(
        "unit13" to hex("00133304000101"),       // accepted, status only — the control case
        "dcas-e00" to dcasFrame(GEAR_USAGE_BODY, 0x00),
        "dcas-e03" to dcasFrame(GEAR_USAGE_BODY, 0x03),
        "dcas-ebd" to dcasFrame(GEAR_USAGE_BODY, 0xBD),
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
