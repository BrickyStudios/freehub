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
     * A BLE write to 0x2AFA. Byte 0 is the BLE sequence header (0x00 for one block ≤ 18 bytes), then the transport
     * control byte, then the payload. The reply on 0x2AF9 leads with `controlInfo | 0x20` (0x13→0x33, 0x03→0x23,
     * 0x0C→0x2C). This is E-TUBE's real BLE framing — the 0xBB/0x48 DCAS frame is the internal serial transport and
     * is never written over BLE (it drew total silence when tried).
     */
    fun ble(controlInfo: Int, vararg payload: Int): ByteArray =
        byteArrayOf(0x00, controlInfo.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    /** A unit command: control 0x13, then group, command, params. Reply `33 <group> <command|2 = data / |3 = NAK>`. */
    fun unitCommand(group: Int, command: Int, vararg params: Int): ByteArray = ble(0x13, group, command, *params)

    /**
     * The PCE "PC-link" session E-TUBE opens right after login — the step Freehub was missing, which is why every
     * feature group answered NAK (`command | 3`) while the ID group (01) answered: `setProtocolMode(0,0)` then
     * `setPCConnectMode(1)`. Until these run the derailleur rejects the config / history / shift reads.
     */
    val SESSION_OPEN: List<Pair<String, ByteArray>> = listOf(
        "setProtocolMode" to ble(0x03, 0x00), // reply 23 xx; xx & 31 = the bus-assigned app slot
        "setPCConnect" to ble(0x0C, 0x01),    // reply 2C xx; the "PC connected" unlock
    )

    /** Set the shift mode (group 0x0B, command 0xF8): 1 = manual front shifting, 0 = synchronized. A write. */
    fun setShiftMode(manual: Boolean) = unitCommand(0x0B, 0xF8, if (manual) 1 else 0, 0)

    /**
     * The developer probe sweep, run after [SESSION_OPEN]: the feature reads that NAKed before the session was
     * opened. If the shift (synchro) config now answers `command | 2` (data) instead of `| 3`, the session was the
     * whole gate. Synchro params are E-TUBE's real `getSynchro(mode 0x10, direction 0, fdPosition 1)`.
     */
    val PROBE_READS: List<Pair<String, ByteArray>> = listOf(
        "fw" to unitCommand(0x01, 0x2C, 0),                 // control: answered even before the session
        "synchro" to unitCommand(0x32, 0xAC, 0x10, 1, 0, 0),
        "sub-synchro" to unitCommand(0x32, 0xEC, 0x10, 1, 0, 0),
        "gear-usage" to unitCommand(0x33, 0x04, 0, 1, 1),
        "date" to unitCommand(0x4A, 0x04, 0),
        "error-log" to unitCommand(0x32, 0xCC, 0),
        "auto-shift" to unitCommand(0x0B, 0xEC, 0),
    )

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
