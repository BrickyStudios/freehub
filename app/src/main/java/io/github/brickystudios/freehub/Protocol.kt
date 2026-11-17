package io.github.brickystudios.freehub

import java.util.UUID

/**
 * What the rear derailleur of a 12-speed Di2 group (RD-R7150, RD-R8150, RD-R9250 …) sends over Bluetooth LE.
 * Layout documented in PROTOCOL.md. Pure, so the JVM tests cover it.
 */
object Sbi {
    private fun shimano(id: Int): UUID = UUID.fromString("%08x-5348-494d-414e-4f5f424c4500".format(id))
    private fun sig(id: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(id))

    /** "Shimano Bicycle Information", the service bike computers read. The low bytes spell SHIMANO_BLE. */
    val SERVICE = shimano(0x18EF)
    val PERIODIC = shimano(0x2AC1)
    val INSTANT = shimano(0x2AC2)

    val BATTERY_SERVICE = sig(0x180F)
    val BATTERY_LEVEL = sig(0x2A19)
    val DEVICE_INFO = sig(0x180A)
    val SERIAL = sig(0x2A25)
    val FIRMWARE = sig(0x2A26)
    val CCCD = sig(0x2902)

    /** Bluetooth SIG company identifier of SHIMANO INC. in the advertisement */
    const val COMPANY_ID = 0x044A

    private const val TYPE_GEARS = 0
    private const val ALERT = 1
    private const val SWITCH = 2

    /** 2AC1 carries several record types; byte 0 says which. Null for anything we do not read. */
    fun periodic(b: ByteArray): Gears? {
        if (b.isEmpty() || b.u(0) != TYPE_GEARS || b.size < 17) return null
        return Gears(
            mode = ShiftMode.of(b.u(1)),
            multiShiftInterval = b.u(2).takeIf { it != 255 },
            front = b.u(3).position(),
            frontMax = b.u(4).position(),
            rear = b.u(5).position(),
            rearMax = b.u(6).position(),
            frontAdjust = b[7].adjust(),
            rearAdjust = b[10].adjust(),
            rearAdjustMin = b[11].adjust(),
            rearAdjustMax = b[12].adjust(),
            chainrings = Teeth.chainrings(b.u(13) or (b.u(14) shl 8)),
            sprockets = Teeth.sprockets(b.u(15) or (b.u(16) shl 8)),
        )
    }

    /** 2AC2: a D-Fly button press (which channels, which kind) or an alert. */
    fun instant(b: ByteArray): Instant? {
        if (b.size < 2) return null
        return when (b.u(0) shr 4) {
            SWITCH -> if (b.size < 5) null else Instant.Buttons(
                seq = b.u(0) and 0x0F,
                channels = (1..4).associateWith { Press.of(b.u(it) shr 4) }.filterValues { it != null }.mapValues { it.value!! },
            )
            ALERT -> Instant.Alert(b.u(1))
            else -> null
        }
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
    private fun Int.position() = takeIf { it in 1..254 }
    private fun Byte.adjust() = toInt().takeIf { it != -128 }
}

enum class ShiftMode(val code: Int) {
    MANUAL(0), ADJUST(1), AUTO(2), PROTECTION_RESET(3), AUTO2(4), SYNCHRO_S1(5), SYNCHRO_S2(6), UNKNOWN(255);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class Press { SHORT, LONG, DOUBLE;

    companion object {
        fun of(nibble: Int) = when (nibble) { 1 -> SHORT; 2 -> LONG; 4 -> DOUBLE; else -> null }
    }
}

sealed interface Instant {
    data class Buttons(val seq: Int, val channels: Map<Int, Press>) : Instant
    data class Alert(val pattern: Int) : Instant
}

/** Positions count from 1. On both derailleurs the highest position is the hardest gear. */
data class Gears(
    val mode: ShiftMode,
    val multiShiftInterval: Int?,
    val front: Int?,
    val frontMax: Int?,
    val rear: Int?,
    val rearMax: Int?,
    val frontAdjust: Int?,
    val rearAdjust: Int?,
    val rearAdjustMin: Int?,
    val rearAdjustMax: Int?,
    /** largest ring first, as Shimano lists them */
    val chainrings: List<Int>?,
    /** smallest cog first */
    val sprockets: List<Int>?,
) {
    val frontTeeth: Int? get() = Teeth.at(chainrings, front, frontMax)
    val rearTeeth: Int? get() = Teeth.at(sprockets, rear, rearMax)

    /**
     * Crank and cassette picked by hand win over what the derailleur reports: it only knows what
     * E-TUBE was once told, and an unknown pattern number leaves the teeth empty. -1 = not picked.
     */
    fun withPicked(rings: Int, cogs: Int) = copy(
        chainrings = Teeth.chainrings(rings) ?: chainrings,
        sprockets = Teeth.sprockets(cogs) ?: sprockets,
    )
}

/** The teeth tables behind the pattern numbers the derailleur reports. */
object Teeth {
    private val CHAINRINGS = mapOf(
        0 to listOf(38, 28), 1 to listOf(36, 26), 2 to listOf(34, 24), 3 to listOf(40, 30, 22),
        4 to listOf(52, 39), 5 to listOf(52, 36), 6 to listOf(50, 34), 7 to listOf(54, 42),
        8 to listOf(55, 42), 9 to listOf(53, 39), 10 to listOf(52, 38), 11 to listOf(46, 36),
        12 to listOf(48, 31), 13 to listOf(46, 30), 14 to listOf(54, 40),
    )
    private val SPROCKETS = mapOf(
        0 to listOf(11, 13, 15, 17, 19, 21, 24, 27, 31, 35, 40),
        1 to listOf(11, 13, 15, 17, 19, 21, 24, 28, 32, 37, 42),
        2 to listOf(11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 25),
        3 to listOf(11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28),
        4 to listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30),
        5 to listOf(12, 13, 14, 15, 16, 17, 18, 19, 21, 23, 25),
        6 to listOf(12, 13, 14, 15, 16, 17, 19, 21, 23, 25, 28),
        7 to listOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 23),
        8 to listOf(11, 12, 13, 14, 16, 18, 20, 22, 25, 28, 32),
        9 to listOf(14, 15, 16, 17, 18, 19, 20, 21, 23, 25, 28),
        10 to listOf(11, 13, 15, 17, 19, 21, 24, 28, 32, 37, 46),
        11 to listOf(11, 13, 15, 17, 19, 21, 23, 25, 27, 30, 34),
        12 to listOf(11, 13, 15, 17, 20, 23, 26, 30, 36, 43, 50),
        13 to listOf(11, 13, 15, 17, 20, 23, 26, 30, 34, 39, 45),
        16 to listOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 24, 28),
        17 to listOf(11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 27, 30),
        18 to listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34),
        19 to listOf(14, 15, 16, 17, 18, 19, 20, 22, 24, 27, 30, 34),
        20 to listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 32, 36),
        21 to listOf(10, 12, 14, 16, 18, 21, 24, 28, 33, 39, 45, 51),
        22 to listOf(9, 11, 13, 15, 17, 19, 21, 24, 28, 33, 39, 45),
        32 to listOf(11, 13, 15, 17, 20, 23, 26, 30, 36, 43),
        33 to listOf(11, 13, 15, 17, 20, 23, 28, 34, 41, 48),
    )

    fun chainrings(pattern: Int) = CHAINRINGS[pattern]
    fun sprockets(pattern: Int) = SPROCKETS[pattern]

    /** pattern numbers that fit a drivetrain with this many rings or cogs, for picking by hand */
    fun ringPatterns(rings: Int) = CHAINRINGS.filterValues { it.size == rings }.keys.sorted()
    fun cogPatterns(cogs: Int) = SPROCKETS.filterValues { it.size == cogs }.keys.sorted()

    /** Shimano indexes from the hardest gear: pattern[max - position]. */
    fun at(pattern: List<Int>?, position: Int?, max: Int?): Int? {
        if (pattern == null || position == null || max == null || pattern.size != max || position !in 1..max) return null
        return pattern[max - position]
    }
}
