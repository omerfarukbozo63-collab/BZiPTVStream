package com.bz.iptvstream

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Modeller ----------
enum class Type(val label: String) { LIVE("Canlı TV"), MOVIE("Filmler"), SERIES("Diziler") }

data class Profile(
    val id: String, val name: String, val url: String, val user: String, val pass: String,
    val fmt: String = "ts", val groupByCountry: Boolean = true, val hideAdult: Boolean = true
) {
    val xtream get() = user.isNotBlank()
    fun base() = url.trim().trimEnd('/')
    fun m3uUrl() = if (!xtream) url.trim()
    else "${base()}/get.php?username=$user&password=$pass&type=m3u_plus&output=${if (fmt == "ts") "ts" else "m3u8"}"
}

data class Ep(val season: Int, val num: Int, val title: String, val url: String)

data class Item(
    val key: String, val name: String, val group: String, val url: String, val type: Type,
    val logo: String = "", val streamId: String = "", val seriesId: String = "",
    val eps: List<Ep> = emptyList(), val vodId: String = "", val archive: Boolean = false
) {
    val isSeriesFolder get() = type == Type.SERIES && (seriesId.isNotBlank() || eps.isNotEmpty())
}

data class Playable(
    val url: String, val title: String, val streamId: String? = null,
    val archive: Boolean = false, val key: String = url, val openPast: Boolean = false
)

data class Epg(val title: String, val start: Long, val end: Long)
data class Past(val title: String, val minutes: Long, val start: String)

// ---------- Ülke ayrıştırma ----------
val COUNTRY = mapOf(
    "TR" to "Türkiye", "TURKEY" to "Türkiye", "TURKIYE" to "Türkiye", "TÜRKIYE" to "Türkiye", "TURK" to "Türkiye",
    "DE" to "Almanya", "GERMANY" to "Almanya", "UK" to "Birleşik Krallık", "GB" to "Birleşik Krallık",
    "FR" to "Fransa", "FRANCE" to "Fransa", "NL" to "Hollanda", "AZ" to "Azerbaycan", "AZERBAIJAN" to "Azerbaycan",
    "US" to "ABD", "USA" to "ABD", "IT" to "İtalya", "ES" to "İspanya", "AR" to "Arapça", "RU" to "Rusya",
    "PL" to "Polonya", "BG" to "Bulgaristan", "GR" to "Yunanistan", "RO" to "Romanya", "PT" to "Portekiz",
    "BE" to "Belçika", "AT" to "Avusturya", "CH" to "İsviçre", "SE" to "İsveç", "CA" to "Kanada",
    "IN" to "Hindistan", "IR" to "İran", "AL" to "Arnavutluk", "XK" to "Kosova", "KU" to "Kürtçe"
)

private val COUNTRY_RE = Regex("^[^\\p{L}]*(\\p{L}{2,10})[^\\p{L}]*(.*)$")

fun countryOf(group: String): Pair<String, String> {
    val m = COUNTRY_RE.find(group.trim()) ?: return "Diğer" to group
    val name = COUNTRY[m.groupValues[1].uppercase(Locale.ROOT)]
    return if (name != null) name to m.groupValues[2].ifBlank { "Genel" } else "Diğer" to group
}

fun isAdult(g: String): Boolean {
    val l = g.lowercase(Locale.ROOT)
    return listOf("adult", "xxx", "+18", "18+", "erotik", "porn").any { it in l }
}

// ---------- Ağ ----------
fun fetch(url: String): String {
    val c = URL(url).openConnection()
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
    c.connectTimeout = 15000; c.readTimeout = 90000
    return c.getInputStream().bufferedReader().readText()
}

fun JSONArray.objs(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun js(o: JSONObject, k: String): String = o.opt(k)?.toString()?.takeIf { it != "null" } ?: ""
fun b64(s: String): String = try { String(Base64.decode(s, Base64.DEFAULT)) } catch (e: Exception) { s }

private fun api(p: Profile) = "${p.base()}/player_api.php?username=${p.user}&password=${p.pass}"

fun classify(url: String, group: String): Type {
    val u = url.lowercase(); val g = group.lowercase()
    return when {
        "/movie/" in u -> Type.MOVIE
        "/series/" in u -> Type.SERIES
        listOf("vod", "film", "movie", "sinema").any { it in g } -> Type.MOVIE
        listOf("dizi", "series").any { it in g } -> Type.SERIES
        else -> Type.LIVE
    }
}

private val SE = Regex("(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})")

fun parseM3u(text: String): List<Item> {
    val out = mutableListOf<Item>()
    val series = linkedMapOf<String, Triple<String, String, MutableList<Ep>>>()
    var name = ""; var group = "Diğer"; var logo = ""
    for (raw in text.lineSequence()) {
        val l = raw.trim()
        if (l.startsWith("#EXTINF")) {
            group = Regex("group-title=\"(.*?)\"").find(l)?.groupValues?.get(1)?.ifBlank { null } ?: "Diğer"
            logo = Regex("tvg-logo=\"(.*?)\"").find(l)?.groupValues?.get(1) ?: ""
            name = l.substringAfterLast(",").trim()
        } else if (l.isNotEmpty() && !l.startsWith("#")) {
            val t = classify(l, group); val n = name.ifBlank { l }
            val m = if (t == Type.SERIES) SE.find(n) else null
            if (m != null) {
                val title = m.groupValues[1].trim()
                series.getOrPut(title) { Triple(group, logo, mutableListOf()) }.third
                    .add(Ep(m.groupValues[2].toInt(), m.groupValues[3].toInt(), n, l))
            } else out += Item(l, n, group, l, t, logo)
            name = ""; group = "Diğer"; logo = ""
        }
    }
    series.forEach { (t, v) ->
        out += Item("s:$t", t, v.first, "", Type.SERIES, v.second,
            eps = v.third.sortedWith(compareBy({ it.season }, { it.num })))
    }
    return out
}

suspend fun loadXtream(p: Profile): List<Item> = coroutineScope {
    val b = p.base(); val a = api(p)
    fun arr(act: String) = JSONArray(fetch("$a&action=$act"))
    fun cats(act: String) = arr(act).objs().associate { js(it, "category_id") to js(it, "category_name") }
    val live = async(Dispatchers.IO) {
        val c = cats("get_live_categories")
        arr("get_live_streams").objs().map { o ->
            val id = js(o, "stream_id")
            Item("l$id", js(o, "name"), c[js(o, "category_id")] ?: "Diğer",
                "$b/live/${p.user}/${p.pass}/$id.${if (p.fmt == "ts") "ts" else "m3u8"}", Type.LIVE,
                js(o, "stream_icon"), streamId = id, archive = js(o, "tv_archive") == "1")
        }
    }
    val vod = async(Dispatchers.IO) {
        val c = cats("get_vod_categories")
        arr("get_vod_streams").objs().map { o ->
            val id = js(o, "stream_id")
            val ext = js(o, "container_extension").ifBlank { "mp4" }
            Item("v$id", js(o, "name"), c[js(o, "category_id")] ?: "Diğer",
                "$b/movie/${p.user}/${p.pass}/$id.$ext", Type.MOVIE, js(o, "stream_icon"), vodId = id)
        }
    }
    val ser = async(Dispatchers.IO) {
        val c = cats("get_series_categories")
        arr("get_series").objs().map { o ->
            val id = js(o, "series_id")
            Item("s$id", js(o, "name"), c[js(o, "category_id")] ?: "Diğer", "", Type.SERIES,
                js(o, "cover"), seriesId = id)
        }
    }
    live.await() + vod.await() + ser.await()
}

fun loadEps(p: Profile, sid: String): List<Ep> {
    val b = p.base()
    val j = JSONObject(fetch("${api(p)}&action=get_series_info&series_id=$sid")).optJSONObject("episodes")
        ?: return emptyList()
    val out = mutableListOf<Ep>()
    j.keys().forEach { s ->
        j.getJSONArray(s).objs().forEach { e ->
            out += Ep(s.toIntOrNull() ?: 1, js(e, "episode_num").toIntOrNull() ?: 0, js(e, "title"),
                "$b/series/${p.user}/${p.pass}/${js(e, "id")}.${js(e, "container_extension").ifBlank { "mp4" }}")
        }
    }
    return out.sortedWith(compareBy({ it.season }, { it.num }))
}

fun loadEpg(p: Profile, id: String, limit: Int): List<Epg> {
    val a = JSONObject(fetch("${api(p)}&action=get_short_epg&stream_id=$id&limit=$limit"))
        .optJSONArray("epg_listings") ?: return emptyList()
    return a.objs().mapNotNull { o ->
        val s = js(o, "start_timestamp").toLongOrNull() ?: return@mapNotNull null
        val e = js(o, "stop_timestamp").toLongOrNull() ?: return@mapNotNull null
        Epg(b64(js(o, "title")), s, e)
    }
}

fun loadPast(p: Profile, id: String): List<Past> {
    val now = System.currentTimeMillis() / 1000
    val a = JSONObject(fetch("${api(p)}&action=get_simple_data_table&stream_id=$id"))
        .optJSONArray("epg_listings") ?: return emptyList()
    return a.objs().filter { it.optInt("has_archive") == 1 && (js(it, "stop_timestamp").toLongOrNull() ?: 0) < now }
        .takeLast(40).reversed().map { o ->
            val s = js(o, "start")
            val st = js(o, "start_timestamp").toLongOrNull() ?: 0
            val en = js(o, "stop_timestamp").toLongOrNull() ?: 0
            Past(b64(js(o, "title")), (en - st) / 60, s.substring(0, 10) + ":" + s.substring(11, 16).replace(":", "-"))
        }
}

fun loadAccount(p: Profile): List<Pair<String, String>> {
    val u = JSONObject(fetch(api(p))).optJSONObject("user_info") ?: return listOf("Durum" to "Bilgi alınamadı")
    val exp = js(u, "exp_date").toLongOrNull()?.let {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it * 1000))
    } ?: "Süresiz"
    return listOf(
        "Durum" to js(u, "status"), "Bitiş tarihi" to exp,
        "Bağlantı" to "${js(u, "active_cons")} / ${js(u, "max_connections")}",
        "Deneme hesabı" to if (js(u, "is_trial") == "1") "Evet" else "Hayır"
    )
}

data class VodInfo(val meta: List<Pair<String, String>>, val plot: String)

fun loadVodInfo(p: Profile, id: String): VodInfo {
    val i = JSONObject(fetch("${api(p)}&action=get_vod_info&vod_id=$id")).optJSONObject("info") ?: return VodInfo(emptyList(), "")
    val meta = listOf("Puan" to "rating", "Yıl" to "releasedate", "Tür" to "genre", "Süre" to "duration",
        "Yönetmen" to "director", "Oyuncular" to "cast")
        .map { it.first to js(i, it.second) }.filter { it.second.isNotBlank() }
    return VodInfo(meta, js(i, "plot"))
}

// ---------- Kayıt ----------
fun loadProfiles(sp: android.content.SharedPreferences): List<Profile> =
    JSONArray(sp.getString("profiles", "[]")).objs().map {
        Profile(js(it, "id"), js(it, "n"), js(it, "u"), js(it, "x"), js(it, "y"),
            js(it, "f").ifBlank { "ts" }, it.optBoolean("g", true), it.optBoolean("h", true))
    }

fun saveProfiles(sp: android.content.SharedPreferences, l: List<Profile>) {
    val a = JSONArray()
    l.forEach {
        a.put(JSONObject().put("id", it.id).put("n", it.name).put("u", it.url).put("x", it.user)
            .put("y", it.pass).put("f", it.fmt).put("g", it.groupByCountry).put("h", it.hideAdult))
    }
    sp.edit().putString("profiles", a.toString()).apply()
}

fun fmtTime(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}
