package io.github.brickystudios.freehub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance from GPS fixes, robust against the jitter of a standing phone and the jumps of a bad fix.
 * Only the distance is kept, never a position history. Pure, so the JVM tests cover it.
 */
class Track {
    private var lat = Double.NaN
    private var lon = 0.0
    private var at = 0L

    /** Metres to add for this fix (0 for a poor fix, jitter, or a jump). */
    fun add(lat: Double, lon: Double, accuracy: Float, time: Long): Double {
        if (accuracy > MAX_ACCURACY) return 0.0
        if (this.lat.isNaN()) return anchor(lat, lon, time, 0.0)
        val d = metres(this.lat, this.lon, lat, lon)
        val s = (time - at) / 1000.0
        // faster than a bike goes: a bad fix or a gap; start again from here
        if (s > 0 && d / s > MAX_SPEED) return anchor(lat, lon, time, 0.0)
        // standing still, the fix wanders a few metres; wait until the bike has really moved
        if (d < MIN_STEP) return 0.0
        return anchor(lat, lon, time, d)
    }

    fun reset() { lat = Double.NaN }

    private fun anchor(lat: Double, lon: Double, time: Long, d: Double): Double {
        this.lat = lat
        this.lon = lon
        at = time
        return d
    }

    companion object {
        const val MAX_ACCURACY = 25f
        /** 30 m/s = 108 km/h, above any descent */
        const val MAX_SPEED = 30.0
        const val MIN_STEP = 8.0

        /** great-circle distance, good to well under a metre at bike scale */
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_008.8
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}

enum class Loc { NONE, IN_USE, ALWAYS }

/**
 * GPS while a ride is counted, through Android's own GPS provider: no Google services needed (GrapheneOS).
 * Started and stopped by [RideService]; fixes go straight to [Counter].
 */
@SuppressLint("MissingPermission") // start() checks the permission
object Gps {
    private var manager: LocationManager? = null

    fun access(c: Context): Loc = when {
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> Loc.NONE
        Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED -> Loc.IN_USE
        else -> Loc.ALWAYS
    }

    // all four methods, because before Android 11 the last three have no default and a lambda would crash
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = Counter.onFix(l.latitude, l.longitude, l.accuracy, l.time)
        @Deprecated("Android 10") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start(c: Context) {
        if (manager != null || access(c) == Loc.NONE) return
        val m = c.getSystemService(LocationManager::class.java) ?: return
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper()) }
            .onSuccess { manager = m }
    }

    fun stop() {
        manager?.removeUpdates(listener)
        manager = null
    }
}
