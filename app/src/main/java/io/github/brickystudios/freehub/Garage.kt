package io.github.brickystudios.freehub

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One paired bike, known by its rear derailleur's Bluetooth address. Two bikes of the same model
 * (two brothers, one garage) differ only there, so the address is what keeps them apart.
 */
data class Profile(
    val address: String,
    val name: String,
    /** what the derailleur advertises, e.g. "RDR7150 123" */
    val model: String? = null,
    /** crank and cassette pattern picked by hand; -1 = take what the derailleur reports */
    val rings: Int = -1,
    val cogs: Int = -1,
    /** tyre circumference in mm; 2136 = 700x28C */
    val wheel: Int = 2136,
    /** the first gear report of the last connection, as hex, so offline screens know rings and cogs */
    val raw: String? = null,
    /** what the derailleur last reported about itself (Bike → Derailleur), and when */
    val config: Etube.Config? = null,
    val configAt: Long = 0,
) {
    /** file-name part for this bike's statistics and battery log */
    val key get() = address.filter(Char::isLetterOrDigit).lowercase()

    fun toJson(): JSONObject = JSONObject().put("address", address).put("name", name).put("model", model)
        .put("rings", rings).put("cogs", cogs).put("wheel", wheel).put("raw", raw)
        .put("config", config?.toJson()).put("configAt", configAt)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            address = o.getString("address"),
            name = o.getString("name"),
            model = o.optString("model").takeIf { it.isNotEmpty() && !o.isNull("model") },
            rings = o.optInt("rings", -1),
            cogs = o.optInt("cogs", -1),
            wheel = o.optInt("wheel", 2136),
            raw = o.optString("raw").takeIf { it.isNotEmpty() && !o.isNull("raw") },
            config = o.optJSONObject("config")?.let(Etube.Config::fromJson),
            configAt = o.optLong("configAt"),
        )
    }
}

enum class Pick { CONNECT, LATER, IGNORE }

/**
 * What to do with a derailleur heard while looking for a bike. Pure, so the JVM tests cover it.
 * Strangers are ignored; the chosen bike is connected at once; another paired bike only after the chosen one
 * stayed silent for a moment, so two paired bikes standing side by side never swap on their own.
 */
fun pick(address: String, active: String?, known: Set<String>): Pick = when {
    address !in known -> Pick.IGNORE
    active == null || address == active || active !in known -> Pick.CONNECT
    else -> Pick.LATER
}

/** The garage file: the bikes and which one the screens show. Pure, so the JVM tests cover it. */
object GarageFile {
    fun write(bikes: List<Profile>, active: String?): String =
        JSONObject().put("active", active).put("bikes", JSONArray().apply { bikes.forEach { put(it.toJson()) } }).toString()

    fun read(s: String): Pair<List<Profile>, String?> {
        val o = JSONObject(s)
        val a = o.optJSONArray("bikes") ?: JSONArray()
        val bikes = (0 until a.length()).map { Profile.fromJson(a.getJSONObject(it)) }
        val active = o.optString("active").takeIf { !o.isNull("active") && bikes.any { b -> b.address == it } }
        return bikes to (active ?: bikes.firstOrNull()?.address)
    }

    /** "Mein Rad" for the first, then "Rad 2", "Rad 3", skipping names already taken */
    fun defaultName(bikes: List<Profile>, first: String, nth: (Int) -> String): String {
        if (bikes.isEmpty()) return first
        val taken = bikes.map { it.name }.toSet()
        return generateSequence(bikes.size + 1) { it + 1 }.map(nth).first { it !in taken }
    }
}

/**
 * The paired bikes. Freehub only ever connects to these, so a second bike of the same model next to yours
 * stays untouched. Statistics, distance and the battery log live in one file per bike.
 */
object Garage {
    val bikes = MutableStateFlow<List<Profile>>(emptyList())
    /** address of the bike the screens show: the one connected, else the last one picked */
    val active = MutableStateFlow<String?>(null)
    val current: Profile? get() = bikes.value.firstOrNull { it.address == active.value }

    private var app: Context? = null
    private lateinit var file: File

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        file = File(ctx.filesDir, "bikes.json")
        val (list, act) = runCatching { GarageFile.read(file.readText()) }.getOrDefault(emptyList<Profile>() to null)
        bikes.value = list
        active.value = act
        openData(ctx)
    }

    fun isKnown(address: String) = bikes.value.any { it.address == address }

    /**
     * Pairs a derailleur. The first bike ever paired takes over the rides, distance and settings
     * counted before bikes existed (Freehub up to 0.5), because they came from that one derailleur.
     */
    fun add(address: String, model: String?): Profile {
        val ctx = app ?: error("Garage.init first")
        bikes.value.firstOrNull { it.address == address }?.let { select(it.address); return it }
        var p = Profile(address, GarageFile.defaultName(bikes.value, tr("Mein Rad", "My bike")) { tr("Rad $it", "Bike $it") }, model)
        if (bikes.value.isEmpty()) {
            File(ctx.filesDir, "stats.json").renameTo(File(ctx.filesDir, "stats-${p.key}.json"))
            File(ctx.filesDir, "battery.json").renameTo(File(ctx.filesDir, "battery-${p.key}.json"))
            p = p.copy(rings = Prefs.legacyRings(ctx), cogs = Prefs.legacyCogs(ctx), wheel = Prefs.legacyWheel(ctx), raw = Prefs.legacyRaw(ctx))
        }
        bikes.value = bikes.value + p
        select(address)
        return p
    }

    /** Shows another bike. The ride in progress ends first, so it is counted for the bike it was ridden on. */
    fun select(address: String) {
        if (!isKnown(address)) return
        if (active.value == address) return save()
        active.value = address
        save()
        app?.let(::openData)
    }

    fun update(address: String? = active.value, change: (Profile) -> Profile) {
        bikes.value = bikes.value.map { if (it.address == address) change(it) else it }
        save()
    }

    /** Forgets a bike and deletes its rides and battery log. */
    fun remove(address: String) {
        val ctx = app ?: return
        val p = bikes.value.firstOrNull { it.address == address } ?: return
        bikes.value = bikes.value - p
        if (active.value == address) active.value = bikes.value.firstOrNull()?.address
        save()
        openData(ctx)
        listOf("stats", "battery").forEach { File(ctx.filesDir, "$it-${p.key}.json").delete() }
    }

    private fun openData(ctx: Context) {
        val key = current?.key
        Counter.open(ctx, key)
        Power.open(ctx, key)
    }

    private fun save() {
        if (app == null) return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(GarageFile.write(bikes.value, active.value))
        tmp.renameTo(file)
    }
}
