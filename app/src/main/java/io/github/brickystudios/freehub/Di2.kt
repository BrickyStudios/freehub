package io.github.brickystudios.freehub

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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

    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (bike.value.demo) return
        wanted = true
        if (gatt == null && !scanning) scan()
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
            stopScan()
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
                    Counter.linkLost()
                    bike.update { it.copy(link = Link.SEARCHING, buttons = null) }
                    if (wanted) scan()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (g == gatt) setUp(g) }
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
        when (uuid) {
            Sbi.PERIODIC -> Sbi.periodic(v)?.let { gears ->
                if (bike.value.gears == null) Prefs.setLastGears(app, v)
                bike.update { it.copy(gears = gears) }
                Counter.onGears(gears)
            }
            Sbi.INSTANT -> (Sbi.instant(v) as? Instant.Buttons)?.let { b -> bike.update { it.copy(buttons = b) } }
            Sbi.BATTERY_LEVEL -> v.firstOrNull()?.let { b -> bike.update { it.copy(battery = b.toInt() and 0xFF) } }
            Sbi.FIRMWARE -> bike.update { it.copy(firmware = v.decodeToString().trim()) }
            Sbi.SERIAL -> bike.update { it.copy(serial = v.decodeToString().trim()) }
        }
    }

    /** Demo mode feeds the same path as a real derailleur. */
    fun feed(b: Bike) {
        bike.value = b
        b.gears?.let(Counter::onGears)
    }

    private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    private const val GATT_INSUFFICIENT_ENCRYPTION = 15
}
