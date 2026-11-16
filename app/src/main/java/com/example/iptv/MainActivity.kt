package com.example.iptv

import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Modeller ----------
enum class Type(val label: String) { LIVE("Canlı TV"), MOVIE("Filmler (VOD)"), SERIES("Diziler") }
data class Profile(val name: String, val url: String, val user: String, val pass: String, val fmt: String = "m3u8") {
    val xtream get() = user.isNotBlank()
    fun m3uUrl() = if (!xtream) url
    else "${url.trimEnd('/')}/get.php?username=$user&password=$pass&type=m3u_plus&output=$fmt"
}
data class Ep(val season: Int, val num: Int, val title: String, val url: String)
data class Item(
    val key: String, val name: String, val group: String, val url: String, val type: Type,
    val logo: String = "", val streamId: String = "", val seriesId: String = "", val eps: List<Ep> = emptyList(),
    val vodId: String = "", val archive: Boolean = false
) { val isSeriesFolder get() = type == Type.SERIES && (seriesId.isNotBlank() || eps.isNotEmpty()) }
data class Playable(val url: String, val title: String, val streamId: String? = null, val archive: Boolean = false)

// ---------- Ağ / Ayrıştırma ----------
fun fetch(url: String): String {
    val c = URL(url).openConnection()
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV)")
    c.connectTimeout = 15000; c.readTimeout = 90000
    return c.getInputStream().bufferedReader().readText()
}
fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

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

val SE = Regex("(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})")

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
    series.forEach { (t, v) -> out += Item("s:$t", t, v.first, "", Type.SERIES, v.second,
        eps = v.third.sortedWith(compareBy({ it.season }, { it.num }))) }
    return out
}

// Xtream Codes API: kategori + canlı + vod + dizi paralel yüklenir (M3U'dan çok daha hızlı)
suspend fun loadXtream(p: Profile): List<Item> = coroutineScope {
    val b = p.url.trimEnd('/'); val api = "$b/player_api.php?username=${p.user}&password=${p.pass}"
    fun arr(a: String) = JSONArray(fetch("$api&action=$a"))
    fun cats(a: String) = arr(a).objs().associate { it.optString("category_id") to it.optString("category_name") }
    val live = async(Dispatchers.IO) {
        val c = cats("get_live_categories")
        arr("get_live_streams").objs().map { o -> val id = o.optString("stream_id")
            Item("l$id", o.optString("name"), c[o.optString("category_id")] ?: "Diğer",
                "$b/live/${p.user}/${p.pass}/$id.${p.fmt}", Type.LIVE, o.optString("stream_icon"), streamId = id,
                archive = o.optInt("tv_archive") == 1) }
    }
    val vod = async(Dispatchers.IO) {
        val c = cats("get_vod_categories")
        arr("get_vod_streams").objs().map { o -> val id = o.optString("stream_id")
            Item("v$id", o.optString("name"), c[o.optString("category_id")] ?: "Diğer",
                "$b/movie/${p.user}/${p.pass}/$id.${o.optString("container_extension", "mp4")}",
                Type.MOVIE, o.optString("stream_icon"), vodId = id) }
    }
    val ser = async(Dispatchers.IO) {
        val c = cats("get_series_categories")
        arr("get_series").objs().map { o -> val id = o.optString("series_id")
            Item("s$id", o.optString("name"), c[o.optString("category_id")] ?: "Diğer", "",
                Type.SERIES, o.optString("cover"), seriesId = id) }
    }
    live.await() + vod.await() + ser.await()
}

fun loadEps(p: Profile, sid: String): List<Ep> {
    val b = p.url.trimEnd('/')
    val j = JSONObject(fetch("$b/player_api.php?username=${p.user}&password=${p.pass}&action=get_series_info&series_id=$sid"))
        .optJSONObject("episodes") ?: return emptyList()
    val out = mutableListOf<Ep>()
    j.keys().forEach { s -> j.getJSONArray(s).objs().forEach { e ->
        out += Ep(s.toIntOrNull() ?: 1, e.optInt("episode_num"), e.optString("title"),
            "$b/series/${p.user}/${p.pass}/${e.optString("id")}.${e.optString("container_extension", "mp4")}") } }
    return out.sortedWith(compareBy({ it.season }, { it.num }))
}

fun loadEpg(p: Profile, streamId: String): String {
    val b = p.url.trimEnd('/'); val f = SimpleDateFormat("HH:mm", Locale.getDefault())
    val a = JSONObject(fetch("$b/player_api.php?username=${p.user}&password=${p.pass}&action=get_short_epg&stream_id=$streamId&limit=3"))
        .optJSONArray("epg_listings") ?: return ""
    return a.objs().mapIndexed { i, o ->
        val t = String(Base64.decode(o.optString("title"), Base64.DEFAULT))
        val s = f.format(Date(o.optLong("start_timestamp") * 1000))
        (if (i == 0) "Şimdi" else "Sonra") + ": $s  $t" }.joinToString("\n")
}

// ---------- Kayıt (profil, favori, son izlenen) ----------
fun sp(c: Context) = c.getSharedPreferences("iptv", 0)
fun getList(c: Context, k: String): List<String> =
    JSONArray(sp(c).getString(k, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
fun putList(c: Context, k: String, l: List<String>) = sp(c).edit().putString(k, JSONArray(l).toString()).apply()
fun loadProfiles(c: Context) = JSONArray(sp(c).getString("p", "[]")).objs()
    .map { Profile(it.getString("n"), it.getString("u"), it.getString("x"), it.getString("y"), it.optString("f", "m3u8")) }
fun saveProfiles(c: Context, l: List<Profile>) {
    val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("x", it.user).put("y", it.pass).put("f", it.fmt)) }
    sp(c).edit().putString("p", a.toString()).apply()
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(this) } } }
    }
}

@Composable
fun App(ctx: Context) {
    var profiles by remember { mutableStateOf(loadProfiles(ctx)) }
    var active by remember { mutableStateOf<Profile?>(null) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var favs by remember { mutableStateOf<List<String>>(emptyList()) }
    var recs by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<Playable?>(null) }
    var series by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()
    val p = active

    fun addRecent(key: String) {
        recs = (listOf(key) + recs.filter { it != key }).take(30)
        putList(ctx, "rec_${p?.name}", recs)
    }

    if (playing != null && p != null) {
        Player(playing!!, p, items.filter { it.type == Type.LIVE }) { playing = null }
    } else if (series != null && p != null) {
        SeriesScreen(p, series!!, { playing = it }) { series = null }
    } else if (p == null) {
        ProfileScreen(profiles, status,
            onAdd = { profiles = profiles + it; saveProfiles(ctx, profiles) },
            onDelete = { profiles = profiles - it; saveProfiles(ctx, profiles) },
            onSelect = { pr ->
                status = "Yükleniyor..."
                scope.launch {
                    try {
                        items = withContext(Dispatchers.IO) {
                            if (pr.xtream) runCatching { loadXtream(pr) }.getOrElse { parseM3u(fetch(pr.m3uUrl())) }
                            else parseM3u(fetch(pr.m3uUrl()))
                        }
                        favs = getList(ctx, "fav_${pr.name}"); recs = getList(ctx, "rec_${pr.name}")
                        if (items.isEmpty()) status = "Kanal bulunamadı" else { active = pr; status = "" }
                    } catch (e: Exception) { status = "Hata: ${e.message}" }
                }
            })
    } else {
        BackHandler { active = null }
        Browser(p, items, favs, recs,
            onFav = { k -> favs = if (k in favs) favs - k else favs + k; putList(ctx, "fav_${p.name}", favs) },
            onOpen = { it0 ->
                addRecent(it0.key)
                if (it0.isSeriesFolder) series = it0 else playing = Playable(it0.url, it0.name, it0.streamId.ifBlank { null }, it0.archive)
            })
    }
}

@Composable
fun ProfileScreen(profiles: List<Profile>, status: String, onAdd: (Profile) -> Unit,
                  onDelete: (Profile) -> Unit, onSelect: (Profile) -> Unit) {
    var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }; var y by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("m3u8") }
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yeni Profil", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(n, { n = it }, label = { Text("Profil adı") }, singleLine = true)
            OutlinedTextField(u, { u = it }, label = { Text("Sunucu (http://host:port) veya M3U linki") }, singleLine = true)
            OutlinedTextField(x, { x = it }, label = { Text("Kullanıcı adı (M3U ise boş bırak)") }, singleLine = true)
            OutlinedTextField(y, { y = it }, label = { Text("Şifre") }, singleLine = true)
            OutlinedButton(onClick = { f = if (f == "m3u8") "ts" else "m3u8" }) { Text("Canlı yayın formatı: $f (değiştir)") }
            Button(onClick = {
                if (u.isNotBlank()) { onAdd(Profile(n.ifBlank { "Profil" }, u.trim(), x.trim(), y.trim(), f)); n = ""; u = ""; x = ""; y = "" }
            }) { Text("Kaydet") }
        }
        Column(Modifier.weight(1f)) {
            Text("Profiller", style = MaterialTheme.typography.headlineSmall)
            if (status.isNotEmpty()) Text(status)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles) { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSelect(p) }, modifier = Modifier.weight(1f)) { Text(p.name) }
                        OutlinedButton(onClick = { onDelete(p) }) { Text("Sil") }
                    }
                }
            }
        }
    }
}

const val FAV = "★ Favoriler"
const val REC = "🕘 Son İzlenenler"

@Composable
fun ChannelRow(it: Item, fav: Boolean, onFav: () -> Unit, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(it.name, maxLines = 1) },
        leadingContent = if (it.logo.isNotBlank()) {
            { AsyncImage(model = it.logo, contentDescription = null, modifier = Modifier.size(48.dp)) }
        } else null,
        trailingContent = { TextButton(onClick = onFav) { Text(if (fav) "★" else "☆") } },
        modifier = Modifier.clickable { onOpen() })
}

@Composable
fun Browser(prof: Profile, all: List<Item>, favs: List<String>, recs: List<String>, onFav: (String) -> Unit, onOpen: (Item) -> Unit) {
    var tab by remember { mutableStateOf(Type.LIVE) }
    var group by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var adult by remember { mutableStateOf(false) }
    var pinAsk by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinChg by remember { mutableStateOf(false) }
    var newPin by remember { mutableStateOf("") }
    var acc by remember { mutableStateOf<String?>(null) }
    var vod by remember { mutableStateOf<Item?>(null) }
    var vodInfo by remember { mutableStateOf("") }
    val inTab = remember(all, tab, adult) { all.filter { it.type == tab && (adult || !isAdult(it.group)) } }
    val groups = remember(inTab) { inTab.map { it.group }.distinct().sorted() }
    val base = when (group) {
        null -> inTab
        FAV -> inTab.filter { it.key in favs }
        REC -> recs.mapNotNull { k -> inTab.find { it.key == k } }
        else -> inTab.filter { it.group == group }
    }
    val shown = if (q.isBlank()) base else base.filter { it.name.contains(q, true) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Type.values().forEach { t ->
                val c = all.count { it.type == t }
                if (t == tab) Button(onClick = {}) { Text("${t.label} ($c)") }
                else OutlinedButton(onClick = { tab = t; group = null; q = "" }) { Text("${t.label} ($c)") }
            }
            if (prof.xtream) OutlinedButton(onClick = {
                acc = "Yükleniyor..."
                scope.launch { acc = withContext(Dispatchers.IO) { runCatching { loadAccount(prof) }.getOrElse { e -> "Hata: ${e.message}" } } }
            }) { Text("Hesap") }
            OutlinedButton(onClick = { if (adult) adult = false else pinAsk = true }) { Text(if (adult) "🔓 +18" else "🔒 +18") }
            if (adult) OutlinedButton(onClick = { pinChg = true }) { Text("PIN değiştir") }
            OutlinedTextField(q, { q = it }, label = { Text("Ara") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (pinChg) AlertDialog(onDismissRequest = { pinChg = false; newPin = "" },
            title = { Text("Yeni PIN") },
            text = { OutlinedTextField(newPin, { newPin = it }, singleLine = true, label = { Text("Yeni PIN (en az 4 hane)") }) },
            confirmButton = { TextButton(onClick = {
                if (newPin.length >= 4) sp(ctx).edit().putString("pin", newPin).apply()
                pinChg = false; newPin = ""
            }) { Text("Kaydet") } })
        if (pinAsk) AlertDialog(onDismissRequest = { pinAsk = false; pin = "" },
            title = { Text("Ebeveyn PIN") },
            text = { OutlinedTextField(pin, { pin = it }, singleLine = true, label = { Text("PIN (varsayılan 1234)") }) },
            confirmButton = { TextButton(onClick = {
                if (pin == sp(ctx).getString("pin", "1234")) adult = true
                pinAsk = false; pin = ""
            }) { Text("Aç") } })
        acc?.let { a -> AlertDialog(onDismissRequest = { acc = null }, title = { Text("Hesap Bilgisi") }, text = { Text(a) },
            confirmButton = { TextButton(onClick = { acc = null }) { Text("Kapat") } }) }
        vod?.let { v -> AlertDialog(onDismissRequest = { vod = null }, title = { Text(v.name) }, text = { Text(vodInfo) },
            confirmButton = { TextButton(onClick = { vod = null; onOpen(v) }) { Text("Oynat") } },
            dismissButton = { TextButton(onClick = { vod = null }) { Text("Kapat") } }) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(0.35f)) {
                item { ListItem({ Text("Tümü (${inTab.size})") }, Modifier.clickable { group = null }) }
                item { ListItem({ Text(FAV) }, Modifier.clickable { group = FAV }) }
                item { ListItem({ Text(REC) }, Modifier.clickable { group = REC }) }
                items(groups) { g -> ListItem({ Text(g) }, Modifier.clickable { group = g }) }
            }
            LazyColumn(Modifier.weight(0.65f)) {
                items(shown) { it ->
                    ChannelRow(it, it.key in favs, { onFav(it.key) }, {
                        if (it.type == Type.MOVIE && it.vodId.isNotBlank()) {
                            vod = it; vodInfo = "Yükleniyor..."
                            scope.launch { vodInfo = withContext(Dispatchers.IO) { runCatching { loadVodInfo(prof, it.vodId) }.getOrDefault("Bilgi yok") } }
                        } else onOpen(it)
                    })
                }
            }
        }
    }
}

@Composable
fun SeriesScreen(p: Profile, s: Item, onPlay: (Playable) -> Unit, onBack: () -> Unit) {
    var eps by remember { mutableStateOf(s.eps) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(s.key) {
        if (eps.isEmpty() && s.seriesId.isNotBlank())
            try { eps = withContext(Dispatchers.IO) { loadEps(p, s.seriesId) } } catch (e: Exception) { err = e.message ?: "Hata" }
    }
    BackHandler(onBack = onBack)
    val seasons = eps.map { it.season }.distinct().sorted()
    var sel by remember(seasons) { mutableStateOf(seasons.firstOrNull()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(s.name, style = MaterialTheme.typography.headlineSmall)
        if (eps.isEmpty()) Text(if (err.isEmpty()) "Yükleniyor..." else err)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(0.3f)) {
                items(seasons) { n -> ListItem({ Text("Sezon $n") }, Modifier.clickable { sel = n }) }
            }
            LazyColumn(Modifier.weight(0.7f)) {
                items(eps.filter { it.season == sel }) { e ->
                    ListItem({ Text("${e.num}. ${e.title}") }, Modifier.clickable {
                        onPlay(Playable(e.url, "${s.name} S${e.season}E${e.num}"))
                    })
                }
            }
        }
    }
}

fun isAdult(g: String) = listOf("adult", "xxx", "+18", "18+", "erotik", "porn").any { g.lowercase().contains(it) }

fun loadAccount(p: Profile): String {
    val b = p.url.trimEnd('/')
    val j = JSONObject(fetch("$b/player_api.php?username=${p.user}&password=${p.pass}"))
    val u = j.optJSONObject("user_info") ?: return "Bilgi alınamadı"
    val exp = u.optString("exp_date").toLongOrNull()?.let { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it * 1000)) } ?: "Süresiz"
    return "Durum: ${u.optString("status")}\nBitiş: $exp\nAktif bağlantı: ${u.optString("active_cons")} / ${u.optString("max_connections")}\nDeneme hesabı: ${if (u.optString("is_trial") == "1") "Evet" else "Hayır"}"
}

fun loadVodInfo(p: Profile, id: String): String {
    val b = p.url.trimEnd('/')
    val i = JSONObject(fetch("$b/player_api.php?username=${p.user}&password=${p.pass}&action=get_vod_info&vod_id=$id")).optJSONObject("info")
        ?: return "Bilgi yok"
    return listOf("Puan" to i.optString("rating"), "Yıl" to i.optString("releasedate"), "Tür" to i.optString("genre"),
        "Süre" to i.optString("duration"), "Yönetmen" to i.optString("director"), "Oyuncular" to i.optString("cast"),
        "Konu" to i.optString("plot")).filter { it.second.isNotBlank() && it.second != "null" }
        .joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "Bilgi yok" }
}

data class Past(val title: String, val minutes: Long, val start: String)

// Catch-up (geriye dönük izleme): sadece tv_archive=1 olan kanallar
fun loadPast(p: Profile, id: String): List<Past> {
    val b = p.url.trimEnd('/'); val now = System.currentTimeMillis() / 1000
    val a = JSONObject(fetch("$b/player_api.php?username=${p.user}&password=${p.pass}&action=get_simple_data_table&stream_id=$id"))
        .optJSONArray("epg_listings") ?: return emptyList()
    return a.objs().filter { it.optInt("has_archive") == 1 && it.optLong("stop_timestamp") < now }
        .takeLast(30).reversed().map { o ->
            val s = o.optString("start")
            Past(String(Base64.decode(o.optString("title"), Base64.DEFAULT)),
                (o.optLong("stop_timestamp") - o.optLong("start_timestamp")) / 60,
                s.substring(0, 10) + ":" + s.substring(11, 16).replace(":", "-"))
        }
}

fun fmt(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}

data class Opt(val t: String, val f: () -> Unit)

fun tracksOf(p: ExoPlayer, type: Int): List<Pair<String, TrackSelectionOverride>> {
    val out = mutableListOf<Pair<String, TrackSelectionOverride>>()
    p.currentTracks.groups.forEach { g ->
        if (g.type == type) for (i in 0 until g.length) if (g.isTrackSupported(i)) {
            val f = g.getTrackFormat(i)
            out += (f.label ?: f.language ?: "Parça ${out.size + 1}") to TrackSelectionOverride(g.mediaTrackGroup, i)
        }
    }
    return out
}

fun pickTrack(p: ExoPlayer, type: Int, o: TrackSelectionOverride?) {
    val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
    if (o != null) { b.addOverride(o); b.setTrackTypeDisabled(type, false) }
    else if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(type, true)
    p.trackSelectionParameters = b.build()
}

@Composable
fun Player(start: Playable, prof: Profile, zap: List<Item>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(start) }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var epg by remember { mutableStateOf("") }
    var past by remember { mutableStateOf<List<Past>>(emptyList()) }
    var showPast by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(0) }
    var resize by remember { mutableStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    var posText by remember { mutableStateOf("") }
    var frac by remember { mutableStateOf(0f) }
    val fr = remember { FocusRequester() }
    val isLive = cur.streamId != null

    fun savePos() {
        if (cur.streamId != null) return
        val d = player.duration; val p = player.currentPosition
        val e = sp(ctx).edit()
        if (d > 0 && p > 10_000 && p < d - 30_000) e.putLong("pos_${cur.url}", p) else e.remove("pos_${cur.url}")
        e.apply()
    }
    DisposableEffect(Unit) { onDispose { savePos(); player.release() } }
    BackHandler {
        when { menu != 0 -> menu = 0; showPast -> showPast = false; else -> { savePos(); onBack() } }
    }
    // Kaldığın yerden devam: kayıtlı konum varsa oradan başlar
    LaunchedEffect(cur.url) {
        val saved = if (cur.streamId == null) sp(ctx).getLong("pos_${cur.url}", 0L) else 0L
        player.setMediaItem(MediaItem.fromUri(cur.url), saved); player.prepare(); player.playWhenReady = true
        player.setPlaybackSpeed(speed)
    }
    LaunchedEffect(cur.url) {
        var n = 0
        while (true) {
            delay(1000)
            if (cur.streamId == null && player.duration > 0) {
                posText = "${fmt(player.currentPosition)} / ${fmt(player.duration)}"
                frac = (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                if (++n % 5 == 0) savePos()
            } else posText = ""
        }
    }
    LaunchedEffect(cur.streamId) {
        epg = ""
        if (cur.streamId != null && prof.xtream) while (true) {
            epg = withContext(Dispatchers.IO) { runCatching { loadEpg(prof, cur.streamId!!) }.getOrDefault("") }
            delay(60_000)
        }
    }
    LaunchedEffect(showPast, menu) { if (!showPast && menu == 0) runCatching { fr.requestFocus() } }
    fun zapBy(d: Int) {
        val i = zap.indexOfFirst { it.streamId == cur.streamId }
        if (i >= 0) { val n = zap[(i + d + zap.size) % zap.size]
            cur = Playable(n.url, n.name, n.streamId.ifBlank { null }, n.archive) }
    }

    Box(Modifier.fillMaxSize().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || showPast || menu != 0) false else when (e.key) {
            Key.Menu -> { menu = 1; true }
            Key.DirectionUp, Key.ChannelUp -> if (isLive) { zapBy(1); true } else { menu = 1; true }
            Key.DirectionDown, Key.ChannelDown -> if (isLive) { zapBy(-1); true } else false
            Key.DirectionLeft -> if (isLive) { if (cur.archive) { showPast = true; true } else false }
                else { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); true }
            Key.DirectionRight -> if (isLive) { menu = 1; true } else { player.seekTo(player.currentPosition + 10_000); true }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { player.playWhenReady = !player.playWhenReady; true }
            else -> false
        }
    }) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false
                isFocusable = false; isFocusableInTouchMode = false } },
            update = { it.resizeMode = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,
                AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)[resize] },
            modifier = Modifier.fillMaxSize())
        Text(cur.title + (if (epg.isNotEmpty()) "\n$epg" else "") +
                (if (isLive) "\n▲▼ Kanal  ▶ Ayarlar" + (if (cur.archive) "  ◀ Geçmiş" else "")
                 else "\n◀▶ ±10 sn  ▲ Ayarlar  OK duraklat"),
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f)).padding(8.dp))
        if (posText.isNotEmpty()) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp)) {
            Text(posText, color = Color.White)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
        }
        if (menu != 0) {
            val opts: List<Opt> = when (menu) {
                1 -> buildList {
                    add(Opt("Görüntü oranı: " + listOf("Sığdır", "Doldur", "Yakınlaştır")[resize]) { resize = (resize + 1) % 3 })
                    add(Opt("Ses dili / parça") { menu = 2 })
                    add(Opt("Altyazı") { menu = 3 })
                    if (!isLive) add(Opt("Hız: ${speed}x") {
                        speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
                        player.setPlaybackSpeed(speed) })
                }
                2 -> listOf(Opt("Varsayılan") { pickTrack(player, C.TRACK_TYPE_AUDIO, null); menu = 0 }) +
                    tracksOf(player, C.TRACK_TYPE_AUDIO).map { (n, o) -> Opt(n) { pickTrack(player, C.TRACK_TYPE_AUDIO, o); menu = 0 } }
                else -> listOf(Opt("Kapalı") { pickTrack(player, C.TRACK_TYPE_TEXT, null); menu = 0 }) +
                    tracksOf(player, C.TRACK_TYPE_TEXT).map { (n, o) -> Opt(n) { pickTrack(player, C.TRACK_TYPE_TEXT, o); menu = 0 } }
            }
            AlertDialog(onDismissRequest = { menu = 0 }, title = { Text("Ayarlar") },
                text = { LazyColumn { items(opts) { o -> TextButton(onClick = o.f) { Text(o.t) } } } },
                confirmButton = { TextButton(onClick = { menu = 0 }) { Text("Kapat") } })
        }
        if (showPast) {
            LaunchedEffect(Unit) {
                past = withContext(Dispatchers.IO) { runCatching { loadPast(prof, cur.streamId!!) }.getOrDefault(emptyList()) }
            }
            Column(Modifier.align(Alignment.CenterStart).width(420.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.85f))) {
                Text("Geçmiş yayınlar", Modifier.padding(12.dp), color = Color.White)
                if (past.isEmpty()) Text("Yükleniyor / kayıt yok", Modifier.padding(12.dp), color = Color.White)
                LazyColumn {
                    items(past) { pp ->
                        ListItem({ Text(pp.title) }, Modifier.clickable {
                            val b = prof.url.trimEnd('/')
                            cur = Playable("$b/timeshift/${prof.user}/${prof.pass}/${pp.minutes}/${pp.start}/${cur.streamId}.${prof.fmt}",
                                pp.title, cur.streamId, cur.archive)
                            showPast = false
                        })
                    }
                }
            }
        }
    }
}
