package io.github.brickystudios.freehub

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class StravaBike(val id: String, val name: String, val km: Double, val primary: Boolean)
data class Tokens(val access: String, val refresh: String, val expiresAt: Long)

/** The bike's odometer as Strava keeps it. at = when Freehub last asked. */
data class Odo(val bike: String, val km: Double, val at: Long)

/** Strava's answers and addresses. Pure, so the JVM tests cover it. */
object StravaApi {
    const val REDIRECT = "freehub://localhost"
    /** the bikes and their odometers are only in the full profile */
    const val SCOPE = "read,profile:read_all"
    const val TOKEN = "https://www.strava.com/oauth/token"
    const val ATHLETE = "https://www.strava.com/api/v3/athlete"
    const val SETTINGS = "https://www.strava.com/settings/api"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun authUrl(clientId: String, state: String) = "https://www.strava.com/oauth/mobile/authorize?client_id=${enc(clientId)}" +
        "&redirect_uri=${enc(REDIRECT)}&response_type=code&approval_prompt=auto&scope=${enc(SCOPE)}&state=${enc(state)}"

    fun form(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    fun tokens(json: String): Tokens? = runCatching {
        JSONObject(json).run { Tokens(getString("access_token"), getString("refresh_token"), getLong("expires_at")) }
    }.getOrNull()

    /** Strava gives the distance in metres. */
    fun bikes(json: String): List<StravaBike> = runCatching {
        val a = JSONObject(json).optJSONArray("bikes") ?: return emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            StravaBike(it.getString("id"), it.optString("name").ifBlank { it.getString("id") }, it.optDouble("distance", 0.0) / 1000, it.optBoolean("primary"))
        }
    }.getOrDefault(emptyList())

    /** the bike picked before, else the one marked primary, else the first */
    fun pick(bikes: List<StravaBike>, chosen: String?) =
        bikes.firstOrNull { it.id == chosen } ?: bikes.firstOrNull { it.primary } ?: bikes.firstOrNull()
}

/**
 * The derailleur measures no distance, so the kilometres come from Strava: the odometer it keeps per bike
 * counts every ride uploaded there, also the ones Freehub never saw. Needs the rider's own Strava API app,
 * because an open-source app cannot ship a client secret.
 */
object Strava {
    private const val SYNC_EVERY_MS = 15 * 60_000L

    val odo = MutableStateFlow<Odo?>(null)
    val bikes = MutableStateFlow<List<StravaBike>>(emptyList())
    /** a sentence for the settings screen after something went wrong */
    val error = MutableStateFlow<String?>(null)
    private var lastSync = 0L
    private var demo = false

    private fun p(c: Context) = c.getSharedPreferences("strava", Context.MODE_PRIVATE)

    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (odo.value != null || demo) return
        val p = p(ctx)
        odo.value = p.getString("bike", null)?.let { Odo(it, p.getFloat("km", 0f).toDouble(), p.getLong("at", 0)) }
    }

    fun connected(ctx: Context) = p(ctx).getString("refresh", null) != null
    fun clientId(ctx: Context) = p(ctx).getString("id", "") ?: ""
    fun hasSecret(ctx: Context) = !p(ctx).getString("secret", null).isNullOrEmpty()

    /** Demo mode shows a made-up odometer and leaves the real one alone. */
    fun setDemo(on: Boolean) {
        demo = on
        odo.value = if (on) Odo("Demo", 3_412.0, System.currentTimeMillis()) else null
        if (!on) app?.let(::init)
    }

    /** Opens Strava (app or browser) to ask the rider; the answer comes back as freehub://localhost. */
    fun login(ctx: Context, clientId: String, secret: String) {
        val state = UUID.randomUUID().toString()
        p(ctx).edit().putString("id", clientId.trim()).putString("secret", secret.trim()).putString("state", state).apply()
        error.value = null
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StravaApi.authUrl(clientId.trim(), state))))
    }

    /** The redirect from Strava. Only one with the state we sent and the profile scope counts. */
    suspend fun finishLogin(ctx: Context, uri: Uri) {
        val p = p(ctx)
        val code = uri.getQueryParameter("code")
        error.value = when {
            // the Strava app may drop the state on its way back; a wrong one means the answer is not ours
            uri.getQueryParameter("state").let { it != null && it != p.getString("state", null) } -> tr("Strava-Antwort passt nicht zur Anfrage. Bitte noch mal verbinden.", "Strava's answer does not match the request. Please connect again.")
            code == null -> tr("Bei Strava abgebrochen.", "Cancelled at Strava.")
            uri.getQueryParameter("scope")?.contains("profile:read_all") != true -> tr("Freehub braucht die Erlaubnis, dein Profil zu lesen: nur dort stehen deine Räder.", "Freehub needs permission to read your profile: that is where your bikes are.")
            else -> null
        }
        p.edit().remove("state").apply()
        if (error.value != null || code == null) return
        val t = withContext(Dispatchers.IO) {
            runCatching { post(StravaApi.form("client_id" to clientId(ctx), "client_secret" to p.getString("secret", "")!!, "code" to code, "grant_type" to "authorization_code")) }
        }.getOrNull()?.let(StravaApi::tokens)
        if (t == null) {
            error.value = tr("Strava hat die Anmeldung abgelehnt. Stimmen Client-ID und Secret?", "Strava refused the login. Are client ID and secret right?")
            return
        }
        save(ctx, t)
        sync(ctx, force = true)
    }

    /** Fetches the odometer, at most every quarter hour unless forced. Quiet when offline. */
    suspend fun sync(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (demo || !connected(ctx) || (!force && now - lastSync < SYNC_EVERY_MS)) return
        lastSync = now
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val t = fresh(ctx) ?: return@withContext null
                val c = URL(StravaApi.ATHLETE).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Authorization", "Bearer ${t.access}")
                if (c.responseCode == 401) throw Unauthorized()
                c.inputStream.bufferedReader().use { StravaApi.bikes(it.readText()) }
            }.onFailure { if (it is Unauthorized) expired(ctx) }.getOrNull()
        } ?: return
        bikes.value = list
        if (list.isEmpty()) {
            error.value = tr("Bei Strava ist kein Rad eingetragen.", "No bike is set up on Strava.")
            return
        }
        error.value = null
        choose(ctx, StravaApi.pick(list, p(ctx).getString("bike_id", null))!!)
    }

    fun choose(ctx: Context, b: StravaBike) {
        val at = System.currentTimeMillis()
        p(ctx).edit().putString("bike_id", b.id).putString("bike", b.name).putFloat("km", b.km.toFloat()).putLong("at", at).apply()
        if (!demo) odo.value = Odo(b.name, b.km, at)
    }

    fun disconnect(ctx: Context) {
        p(ctx).edit().clear().apply()
        bikes.value = emptyList()
        error.value = null
        if (!demo) odo.value = null
    }

    private fun expired(ctx: Context) {
        p(ctx).edit().remove("access").remove("refresh").remove("expires").apply()
        error.value = tr("Strava-Zugang abgelaufen. Bitte neu verbinden.", "Strava access expired. Please connect again.")
    }

    /** A valid access token, refreshed when it runs out within the next minute. */
    private fun fresh(ctx: Context): Tokens? {
        val p = p(ctx)
        val refresh = p.getString("refresh", null) ?: return null
        val t = Tokens(p.getString("access", "")!!, refresh, p.getLong("expires", 0))
        if (t.expiresAt - 60 > System.currentTimeMillis() / 1000) return t
        val new = StravaApi.tokens(post(StravaApi.form("client_id" to clientId(ctx), "client_secret" to p.getString("secret", "")!!, "grant_type" to "refresh_token", "refresh_token" to refresh)))
            ?: throw IOException("no tokens in Strava's answer")
        save(ctx, new)
        return new
    }

    private fun save(ctx: Context, t: Tokens) =
        p(ctx).edit().putString("access", t.access).putString("refresh", t.refresh).putLong("expires", t.expiresAt).apply()

    private fun post(body: String): String {
        val c = URL(StravaApi.TOKEN).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode == 400 || c.responseCode == 401) throw Unauthorized()
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private class Unauthorized : IOException("Strava said no")
}
