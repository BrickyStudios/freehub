package io.github.brickystudios.freehub

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Link { OFF, NO_BLUETOOTH, SEARCHING, CONNECTING, CONNECTED }

/** A derailleur seen while pairing: not connected, only heard. rssi in dBm, higher = closer. */
data class Nearby(val address: String, val name: String?, val rssi: Int, val at: Long)

data class Bike(
    val link: Link = Link.OFF,
    val name: String? = null,
    val battery: Int? = null,
    val gears: Gears? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val buttons: Instant.Buttons? = null,
    val demo: Boolean = false,
)

/**
 * The one connection to the rear derailleur. Android runs one GATT operation at a time,
 * so every read and subscription goes through [ops]. All callbacks land on the main thread.
 */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_SCAN/CONNECT before start()
object Di2 {
    val bike = MutableStateFlow(Bike())

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var wanted = false
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false
    private var bondReceiver: BroadcastReceiver? = null
    /** the last gear report as it came, for [repick] */
    private var raw: ByteArray? = null

    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (bike.value.demo) return
        // nothing paired yet: never grab a derailleur on our own, the rider picks theirs first
        if (Garage.bikes.value.isEmpty()) {
            bike.update { it.copy(link = Link.OFF) }
            return
        }
        wanted = true
        if (gatt == null && !scanning) scan()
    }

    /** the derailleur Freehub is connected to, if any */
    val connectedAddress: String? get() = gatt?.device?.address

    /** After a bike was removed: drop the link if it was that one, and look for the others. */
    fun forget(ctx: Context, address: String) {
        if (connectedAddress == address || Garage.bikes.value.isEmpty()) stop()
        start(ctx)
    }

    // ---- pairing: list every derailleur in range, connect to none of them ----

    val nearby = MutableStateFlow<Map<String, Nearby>>(emptyMap())
    private var discovering = false

    fun discover(ctx: Context, on: Boolean) {
        app = ctx.applicationContext
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        if (on == discovering) return
        discovering = on
        if (on) {
            nearby.value = emptyMap()
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Sbi.SERVICE)).build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner.startScan(filters, settings, discoverCallback) }.onFailure { discovering = false }
        } else runCatching { scanner.stopScan(discoverCallback) }
    }

    private val discoverCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val a = result.device.address
            val n = Nearby(a, result.scanRecord?.deviceName ?: nearby.value[a]?.name, result.rssi, System.currentTimeMillis())
            main.post { nearby.update { it + (a to n) } }
        }
    }

    /** Disconnects and stops looking. */
    fun stop() {
        wanted = false
        stopScan()
        gatt?.close()
        gatt = null
        ops.clear()
        busy = false
        Counter.linkLost()
        bike.update { Bike(demo = it.demo) }
    }

    fun setDemo(on: Boolean) {
        if (on) stop()
        bike.value = Bike(demo = on)
    }

    private fun scan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            bike.update { it.copy(link = Link.NO_BLUETOOTH) }
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        bike.update { it.copy(link = Link.SEARCHING) }
        scanning = true
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Sbi.SERVICE)).build())
        // a derailleur only advertises for a while after it wakes up, so keep looking in low-power mode
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { scanner.startScan(filters, settings, scanCallback) }.onFailure { scanning = false }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val address = result.device.address
            // a derailleur that is not paired (a brother's bike of the same model next to it): leave it alone
            if (!Garage.isKnown(address)) return
            stopScan()
            main.post { Garage.select(address) }
            connect(result.device, result.scanRecord?.deviceName)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (wanted) main.postDelayed({ if (wanted && gatt == null) scan() }, 5_000)
        }
    }

    private fun connect(device: BluetoothDevice, advertisedName: String?) {
        bike.update { it.copy(link = Link.CONNECTING, name = advertisedName ?: it.name) }
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else {
                    // the derailleur sleeps after a few idle minutes; it advertises again on the next shift
                    g.close()
                    gatt = null
                    ops.clear()
                    busy = false
                    DevLog.note("link lost")
                    endMemory(tr("Verbindung weg nach $memoryPackets Paketen. Mitschnitt teilen hilft.", "Link lost after $memoryPackets packets. Sharing the capture helps."))
                    Counter.linkLost()
                    bike.update { it.copy(link = Link.SEARCHING, buttons = null) }
                    if (wanted) scan()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (DevLog.on) table(g)
                setUp(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) DevLog.add("!!", c.uuid, note = "write failed, status $status")
                next()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, value); next() }
        }

        @Deprecated("before Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value ?: ByteArray(0)
            main.post { if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, v); next() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { onValue(c.uuid, value) }
        }

        @Deprecated("before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value ?: return
            main.post { onValue(c.uuid, v) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) bond(g)
                else next()
            }
        }
    }

    private fun setUp(g: BluetoothGatt) {
        bike.update { it.copy(link = Link.CONNECTED) }
        Counter.linked()
        ops.clear()
        busy = false
        read(Sbi.DEVICE_INFO, Sbi.FIRMWARE)
        read(Sbi.DEVICE_INFO, Sbi.SERIAL)
        read(Sbi.BATTERY_SERVICE, Sbi.BATTERY_LEVEL)
        subscribe(Sbi.BATTERY_SERVICE, Sbi.BATTERY_LEVEL)
        subscribe(Sbi.SERVICE, Sbi.PERIODIC)
        // button presses arrive as indications; some firmware wants a bonded link for those
        subscribe(Sbi.SERVICE, Sbi.INSTANT)
        if (memoryPending) queueMemory() // bonding started the setup over
        next()
    }

    private fun read(service: java.util.UUID, char: java.util.UUID) = ops.addLast { g ->
        val c = g.getService(service)?.getCharacteristic(char) ?: return@addLast false
        g.readCharacteristic(c)
    }

    private fun subscribe(service: java.util.UUID, char: java.util.UUID) = ops.addLast { g ->
        val c = g.getService(service)?.getCharacteristic(char) ?: return@addLast false
        val d = c.getDescriptor(Sbi.CCCD) ?: return@addLast false
        g.setCharacteristicNotification(c, true)
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
    }

    private fun write(service: java.util.UUID, char: java.util.UUID, value: ByteArray) = ops.addLast { g ->
        val c = g.getService(service)?.getCharacteristic(char) ?: return@addLast false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        DevLog.add("tx", char, value)
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
    }

    /** Starts the queue unless an operation is already running. */
    private fun kick() { if (!busy) next() }

    /** Developer options: the derailleur's services and characteristics with their properties. */
    private fun table(g: BluetoothGatt) = g.services.forEach { sv ->
        DevLog.add("sv", sv.uuid)
        sv.characteristics.forEach { c ->
            val p = c.properties
            val props = listOfNotNull(
                "read".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
                "write".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
                "write-nr".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
                "notify".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
                "indicate".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
            )
            DevLog.add("ch", c.uuid, note = "${short(sv.uuid)} " + props.joinToString(","))
        }
    }

    private fun short(u: java.util.UUID) = DevLog.short(u)

    /** Developer options: reads every readable value once. Reading changes nothing on the derailleur. */
    fun readAll() {
        val g = gatt ?: return
        DevLog.note("read all")
        table(g)
        g.services.flatMap { it.characteristics }.filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .forEach { c -> read(c.service.uuid, c.uuid) }
        kick()
    }

    // ---- the derailleur's own memory (0x18FF), developer options, experimental ----
    //
    //   read passkey + nonce → write 01+AES(passkey key, nonce) → 10 01 01
    //   → write 02+AES(app key, nonce) → 10 02 01 → write FF 00, then each candidate gear-usage request
    //   → everything on 2AF9 / 2AFD is logged for [MEMORY_LISTEN_MS] → reconnect for live gears again

    /** a sentence for the screen while reading the memory, null when idle */
    val memory = MutableStateFlow<String?>(null)
    private var memoryPending = false
    private var memoryPasskey: String? = null
    private var memoryNonce: ByteArray? = null
    private var memoryPackets = 0
    private var memoryListening = false
    private const val MEMORY_STEP_MS = 6_000L
    private const val MEMORY_LISTEN_MS = 17_000L

    fun readMemory() {
        if (gatt == null || bike.value.link != Link.CONNECTED || memoryPending) return
        DevLog.on = true
        DevLog.note("memory read: login with E-TUBE handshake")
        memoryPending = true
        memoryPasskey = null
        memoryNonce = null
        memoryPackets = 0
        memoryListening = false
        memory.value = tr("Melde mich am Schaltwerk an …", "Logging in to the derailleur …")
        queueMemory()
        kick()
    }

    private fun queueMemory() {
        subscribe(Etube.SERVICE, Etube.AUTH_CONTROL)
        subscribe(Etube.SERVICE, Etube.PCE_RESPONSE)
        subscribe(Etube.SERVICE, Etube.FREE_RESPONSE)
        read(Etube.SERVICE, Etube.PASSKEY) // readable on some units; six digits or the default
        read(Etube.SERVICE, Etube.NONCE)
        main.removeCallbacks(memoryStuck)
        main.postDelayed(memoryStuck, MEMORY_STEP_MS)
    }

    private val memoryStuck = Runnable { endMemory(tr("Keine Antwort vom Schaltwerk. Mitschnitt teilen hilft.", "No answer from the derailleur. Sharing the capture helps.")) }

    private val memoryDone = Runnable {
        endMemory(tr("Fertig: $memoryPackets Pakete empfangen. Jetzt Mitschnitt teilen.", "Done: $memoryPackets packets received. Now share the capture."))
    }


    /** Ends the memory read and reconnects, because the unit stays in command mode otherwise. */
    private fun endMemory(text: String) {
        main.removeCallbacks(memoryStuck)
        main.removeCallbacks(memoryDone)
        if (!memoryPending) return
        memoryPending = false
        memory.value = text
        DevLog.note("memory read ended: $memoryPackets packets")
        gatt?.disconnect()
    }

    private fun onEtube(uuid: java.util.UUID, v: ByteArray) {
        if (!memoryPending) return
        when (uuid) {
            Etube.PASSKEY -> memoryPasskey = Etube.passkeyOrDefault(v)
            Etube.NONCE -> {
                memoryNonce = v
                write(Etube.SERVICE, Etube.AUTH_CONTROL, Etube.passkeyAnswer(memoryPasskey ?: Etube.DEFAULT_PASSKEY, v))
            }
            Etube.AUTH_CONTROL -> when {
                v.contentEquals(Etube.USER_OK) -> memoryNonce?.let { write(Etube.SERVICE, Etube.AUTH_CONTROL, Etube.appAnswer(it)) }
                v.contentEquals(Etube.USER_WRONG) -> endMemory(tr("Passkey falsch. Er wurde in E-TUBE geändert.", "Wrong passkey: it was changed in E-TUBE."))
                // the unit indicates APP_OK more than once; start the burst only on the first
                v.contentEquals(Etube.APP_OK) && !memoryListening -> {
                    memoryListening = true
                    memory.value = tr("Angemeldet, lese Gangnutzung …", "Logged in, reading gear usage …")
                    write(Etube.SERVICE, Etube.FEATURE, Etube.COMMAND_MODE)
                    // send once; high speed streams every sequence on its own, re-sending would restart it at 1
                    DevLog.note("gear-usage request (high speed, then listen)")
                    write(Etube.SERVICE, Etube.PCE_COMMAND, Etube.GEAR_USAGE)
                    main.postDelayed(memoryDone, MEMORY_LISTEN_MS)
                }
            }
            Etube.PCE_RESPONSE, Etube.FREE_RESPONSE -> {
                memoryPackets++
                memory.value = tr("Empfange … $memoryPackets Pakete", "Receiving … $memoryPackets packets")
            }
        }
        main.removeCallbacks(memoryStuck)
        if (memoryPending && !memoryListening) main.postDelayed(memoryStuck, MEMORY_STEP_MS)
        kick()
    }

    /** Runs the next queued operation; skips any the derailleur does not offer. */
    private fun next() {
        val g = gatt ?: return
        busy = false
        while (ops.isNotEmpty()) {
            if (ops.removeFirst()(g)) {
                busy = true
                return
            }
        }
    }

    /** Pairing is "just works" on Shimano units: no PIN. Once bonded, subscribe again. */
    private fun bond(g: BluetoothGatt) {
        if (bondReceiver == null) {
            bondReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val g2 = gatt ?: return
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> setUp(g2)
                        BluetoothDevice.BOND_NONE -> next() // declined: carry on without button presses
                    }
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(bondReceiver, filter)
        }
        if (g.device.bondState == BluetoothDevice.BOND_NONE) g.device.createBond() else next()
    }

    private fun onValue(uuid: java.util.UUID, v: ByteArray) {
        DevLog.add("rx", uuid, v)
        when (uuid) {
            Etube.PASSKEY, Etube.NONCE, Etube.AUTH_CONTROL, Etube.PCE_RESPONSE, Etube.FREE_RESPONSE -> onEtube(uuid, v)
            Sbi.PERIODIC -> Sbi.periodic(v)?.withPicked(Prefs.rings(app), Prefs.cogs(app))?.let { gears ->
                if (bike.value.gears == null) Prefs.setLastGears(app, v)
                raw = v
                bike.update { it.copy(gears = gears) }
                Counter.onGears(gears)
            }
            Sbi.INSTANT -> (Sbi.instant(v) as? Instant.Buttons)?.let { b -> bike.update { it.copy(buttons = b) } }
            Sbi.BATTERY_LEVEL -> v.firstOrNull()?.let { b ->
                val level = b.toInt() and 0xFF
                bike.update { it.copy(battery = level) }
                Power.record(level, Counter.km())
            }
            Sbi.FIRMWARE -> bike.update { it.copy(firmware = v.decodeToString().trim()) }
            Sbi.SERIAL -> bike.update { it.copy(serial = v.decodeToString().trim()) }
        }
    }

    /** Crank or cassette picked by hand: read the last report again with the new teeth. */
    fun repick() {
        val g = Sbi.periodic(raw ?: return)?.withPicked(Prefs.rings(app), Prefs.cogs(app)) ?: return
        bike.update { it.copy(gears = g) }
    }

    /** Demo mode feeds the same path as a real derailleur. */
    fun feed(b: Bike) {
        bike.value = b
        b.gears?.let(Counter::onGears)
    }

    private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    private const val GATT_INSUFFICIENT_ENCRYPTION = 15
}
