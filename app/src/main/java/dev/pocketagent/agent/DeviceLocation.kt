package dev.pocketagent.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.pocketagent.core.places.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone's location through the platform LocationManager (no Google Play
 * services needed). Returns null unless the user turned sharing on and granted
 * the permission.
 */
class DeviceLocation(private val context: Context, private val enabled: () -> Boolean) {
    private val manager = context.getSystemService(LocationManager::class.java)

    @Volatile
    private var cache: Pair<Long, GeoPoint>? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationOn(): Boolean = manager?.isLocationEnabled == true

    suspend fun current(): GeoPoint? {
        if (!enabled() || !hasPermission() || manager == null) return null
        cache?.let { (at, point) -> if (System.currentTimeMillis() - at < CACHE_MS) return point }
        val location = withTimeoutOrNull(FIX_TIMEOUT_MS) { fresh() } ?: lastKnown() ?: return null
        val point = GeoPoint(location.latitude, location.longitude, address(location).orEmpty())
        cache = System.currentTimeMillis() to point
        return point
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = providers().mapNotNull { runCatching { manager?.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }

    private fun providers(): List<String> = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { p -> runCatching { manager?.isProviderEnabled(p) == true }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(): Location? {
        val provider = providers().firstOrNull() ?: return null
        val lm = manager ?: return null
        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Required on API 28-29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }
    }

    private suspend fun address(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
            val a = list?.firstOrNull() ?: return@withContext null
            listOfNotNull(
                a.thoroughfare?.let { street -> a.subThoroughfare?.let { "$it $street" } ?: street },
                a.locality ?: a.subAdminArea,
                a.adminArea,
                a.countryName
            ).joinToString(", ").ifBlank { a.getAddressLine(0) }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val CACHE_MS = 2 * 60_000L
        private const val FIX_TIMEOUT_MS = 12_000L

        /** Miles and °F for the US, Liberia and Myanmar. */
        fun prefersImperial(): Boolean = Locale.getDefault().country.uppercase(Locale.US) in setOf("US", "LR", "MM")
    }
}
