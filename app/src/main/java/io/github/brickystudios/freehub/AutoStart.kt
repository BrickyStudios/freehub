package io.github.brickystudios.freehub

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Starts the ride counting when the derailleur wakes up, with Freehub closed. Android watches for the
 * derailleur's advertisement itself (Companion Device Manager, Android 12+) and wakes [WakeService],
 * which may start the foreground service from the background. The rider picks the derailleur once.
 */
@RequiresApi(31)
object AutoStart {
    private fun cdm(c: Context) = c.getSystemService(CompanionDeviceManager::class.java)

    @Suppress("DEPRECATION") // the address-based calls work from Android 12 on; the id-based ones need 13
    fun on(c: Context) = cdm(c)?.associations?.isNotEmpty() == true

    /** Shows Android's picker, filtered to Shimano derailleurs. [onPicker] launches the system dialog. */
    fun pick(c: Context, onPicker: (IntentSender) -> Unit, onFail: (String) -> Unit) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(Sbi.SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        cdm(c)?.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("Android 13 calls onAssociationPending, whose default lands here")
            override fun onDeviceFound(chooser: IntentSender) = onPicker(chooser)
            override fun onFailure(error: CharSequence?) = onFail(error?.toString() ?: "")
        }, Handler(Looper.getMainLooper())) ?: onFail("")
    }

    /** The picker's answer: start watching the derailleur it names. */
    @Suppress("DEPRECATION")
    fun picked(c: Context, data: Intent?): Boolean {
        val address = when (val d = data?.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> d.device.address
            is BluetoothDevice -> d.address
            else -> null
        } ?: (if (Build.VERSION.SDK_INT >= 33) data?.getParcelableExtra<AssociationInfo>(CompanionDeviceManager.EXTRA_ASSOCIATION)?.deviceMacAddress?.toString() else null)
        ?: return false
        return runCatching { cdm(c)!!.startObservingDevicePresence(address) }
            .onFailure { Log.w("Freehub", "presence watch failed", it) }.isSuccess
    }

    @Suppress("DEPRECATION")
    fun off(c: Context) {
        val m = cdm(c) ?: return
        m.associations.forEach { a ->
            runCatching { m.stopObservingDevicePresence(a) }
            runCatching { m.disassociate(a) }
        }
    }
}

@RequiresApi(31)
class WakeService : CompanionDeviceService() {
    @Deprecated("Android 12")
    override fun onDeviceAppeared(address: String) = RideService.wake(this)

    @RequiresApi(33)
    override fun onDeviceAppeared(info: AssociationInfo) = RideService.wake(this)

    // gone again: the ride service ends on its own once the derailleur stays away
    @Deprecated("Android 12")
    override fun onDeviceDisappeared(address: String) = Unit

    @RequiresApi(33)
    override fun onDeviceDisappeared(info: AssociationInfo) = Unit
}

/** Shown in the settings: only Android 12 and newer can do this. */
val canAutoStart get() = Build.VERSION.SDK_INT >= 31
