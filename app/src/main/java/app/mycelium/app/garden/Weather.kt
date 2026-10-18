package app.mycelium.app.garden

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** What the window shows outside. */
enum class Sky { CLEAR, CLOUDY, RAIN, STORM, SNOW, FOG }

/** What the jar's plants show: spring blossom, summer green, autumn amber, winter frost. */
enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** The season from the phone's date (southern hemisphere flipped when a live lookup told us the latitude). */
fun seasonNow(southern: Boolean = false): Season {
    val m = Calendar.getInstance().get(Calendar.MONTH)   // 0 = January
    val north = when (m) { 2, 3, 4 -> Season.SPRING; 5, 6, 7 -> Season.SUMMER; 8, 9, 10 -> Season.AUTUMN; else -> Season.WINTER }
    return if (!southern) north else Season.entries[(north.ordinal + 2) % 4]
}

/** A live reading: sky, temperature, when it was taken, and whether the place is south of the equator. */
class LiveWeather(val sky: Sky, val tempC: Double, val at: Long, val southern: Boolean)

/**
 * Optional local weather (Settings > Weather > Use my local weather, off by default). Uses the phone's approximate
 * location rounded to 0.1 degrees (about 11 km) and asks Open-Meteo (free, no account, no key) for the current weather
 * code only. At most once an hour, only while the Terrarium is open. Nothing else is sent; the answer stays on the phone.
 */
object Weather {
    private const val PREFS = "mycelium_weather"
    private const val MAX_AGE = 60 * 60_000L

    fun cached(context: Context): LiveWeather? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (at == 0L) return null
        val sky = runCatching { Sky.valueOf(p.getString("sky", "") ?: "") }.getOrNull() ?: return null
        return LiveWeather(sky, p.getFloat("temp", 0f).toDouble(), at, p.getBoolean("southern", false))
    }

    fun forget(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The cached reading when fresh, otherwise one new lookup (null when there is no permission, location or network). */
    suspend fun refresh(context: Context): LiveWeather? {
        cached(context)?.let { if (System.currentTimeMillis() - it.at < MAX_AGE) return it }
        if (!hasPermission(context)) return cached(context)
        val loc = location(context) ?: return cached(context)
        val lat = (loc.latitude * 10).roundToInt() / 10.0
        val lon = (loc.longitude * 10).roundToInt() / 10.0
        val live = withContext(Dispatchers.IO) { runCatching { fetch(lat, lon) }.getOrNull() } ?: return cached(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("sky", live.sky.name).putFloat("temp", live.tempC.toFloat()).putLong("at", live.at).putBoolean("southern", live.southern).apply()
        return live
    }

    private fun fetch(lat: Double, lon: Double): LiveWeather {
        val url = URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f&current=weather_code,temperature_2m", lat, lon))
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.useCaches = false
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val cur = JSONObject(body).getJSONObject("current")
            return LiveWeather(skyOf(cur.getInt("weather_code")), cur.optDouble("temperature_2m"), System.currentTimeMillis(), lat < 0)
        } finally {
            c.disconnect()
        }
    }

    /** WMO weather codes (as used by Open-Meteo) to what the window shows. */
    fun skyOf(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2, 3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    @SuppressLint("MissingPermission")
    private suspend fun location(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val recent = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 6 * 60 * 60_000L) return recent
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.PASSIVE_PROVIDER
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                runCatching { lm.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) } }
                    .onFailure { cont.resume(null) }
            }
        } ?: recent
    }
}
