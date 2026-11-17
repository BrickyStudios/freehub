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
     * A unit command (BikeBridge's unit-command opcode 0x13): target [slot], opcode 0x13, group, command, params.
     * The reply on 0x2AF9 is `33 <group> <reply command> …`, where the reply command is `command | 2` for data
     * and `command | 3` when the unit has nothing to send. Byte 0 is the slot: E-TUBE sends each command to the
     * slot of the unit that owns it (`UnitProtocol.getSlotNo()`), not always 0 — that is why firmware (a system
     * read) answered at slot 0 but the config reads did not.
     */
    fun unitCommand(slot: Int, group: Int, command: Int, vararg params: Int): ByteArray =
        byteArrayOf(slot.toByte(), 0x13, group.toByte(), command.toByte()) + ByteArray(params.size) { params[it].toByte() }

    /**
     * Set the shift mode (E-TUBE's `DCC_CMD_EXTM_RA_SFT_MODE_SEL_SET_C`): group 0x0B, command 0xF8, then
     * `1` = manual front shifting, `0` = synchronized, and a trailing 0. A write — it changes the derailleur.
     * Sent as a DCAS frame: the Opus review showed the plain 0x13 form reaches only the system responder, so a
     * group-0x0B write has to go through the real transport like the reads.
     */
    fun setShiftMode(manual: Boolean) = dcasCommand(0, 0x0B, 0xF8, if (manual) 1 else 0, 0)

    private fun fcs(bytes: ByteArray) = (bytes.sumOf { it.toInt() and 0xFF } + 1) and 0xFF

    /** Byte-stuff a DCAS body: the flag 0xBB and escape 0xBD become `0xBD, (b xor 0x20)`. */
    private fun stuff(bytes: ByteArray): ByteArray {
        val out = ArrayList<Byte>(bytes.size + 2)
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u == 0xBB || u == 0xBD) { out.add(0xBD.toByte()); out.add((u xor 0x20).toByte()) } else out.add(b)
        }
        return out.toByteArray()
    }

    /**
     * A DCAS frame, the transport E-TUBE uses for every command on 0x2AFA (byte-exact, read from the Dalvik
     * bytecode and round-trip-checked against the receive parser): flag `0xBB`, then the byte-stuffed body
     * `[controlInfo] + data + [FCS]` where FCS = `(controlInfo + Σdata + 1) & 0xFF`, then flag `0xBB`.
     */
    fun dcasFrame(controlInfo: Int, vararg data: Int): ByteArray {
        val body = byteArrayOf(controlInfo.toByte()) + ByteArray(data.size) { data[it].toByte() }
        return byteArrayOf(0xBB.toByte()) + stuff(body + fcs(body).toByte()) + 0xBB.toByte()
    }

    /** A unit command: controlInfo 0x48, then `[slot, group, command, params…]`. */
    fun dcasCommand(slot: Int, group: Int, command: Int, vararg params: Int): ByteArray =
        dcasFrame(0x48, slot, group, command, *params)

    /**
     * The developer probe sweep — the full flow E-TUBE runs, now byte-exact from the decompiled bytecode. The
     * `0x13` form reached only the group-01 system responder; feature groups need the real DCAS transport AND the
     * bus setup that puts the derailleur's bridge into addressable mode (`setProtocolMode` 0x03 / `checkProtocolMode`
     * 0x04 / `setTargetAddress` 0x06, themselves DCAS-framed). A firmware read is sent BEFORE the setup too, to tell
     * apart "wrong framing" from "missing setup". After the setup, the config reads are sent as DCAS frames.
     */
    val PROBE_READS: List<Pair<String, ByteArray>> = buildList {
        add("fw-dcas no-setup" to dcasCommand(0, 0x01, 0x2C, 0)) // isolates framing from bus setup
        add("setmode 40|20" to dcasFrame(0x03, 0x60))            // bus setup: into DCAS/addressable mode
        add("checkmode 1" to dcasFrame(0x04))
        add("setmode 40|00" to dcasFrame(0x03, 0x40))
        add("checkmode 2" to dcasFrame(0x04))
        add("target 1F" to dcasFrame(0x06, 0x1F))                // broadcast address
        add("slots" to dcasFrame(0x1B))                          // PnP: which slots exist
        add("target 00" to dcasFrame(0x06, 0x00))                // address the rear derailleur
        add("fw-dcas" to dcasCommand(0, 0x01, 0x2C, 0))          // feature reads, after setup
        add("sync-dcas" to dcasCommand(0, 0x32, 0xAC, 0x10, 1, 0, 0))
        add("gear-dcas" to dcasCommand(0, 0x33, 0x04, 0, 1, 1))
        add("date-dcas" to dcasCommand(0, 0x4A, 0x04, 0))
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
