package com.bz.iptvstream

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

sealed class Screen {
    data object Profiles : Screen()
    data class ProfileEdit(val p: Profile?) : Screen()
    data object Home : Screen()
    data object Live : Screen()
    data object Guide : Screen()
    data class Grid(val type: Type) : Screen()
    data class Movie(val item: Item) : Screen()
    data class Series(val item: Item) : Screen()
    data object Favs : Screen()
    data object Search : Screen()
    data object Settings : Screen()
}

class AppState(val ctx: Context) {
    private val sp = ctx.getSharedPreferences("bz", Context.MODE_PRIVATE)

    var profiles by mutableStateOf(loadProfiles(sp))
    var profile by mutableStateOf<Profile?>(null)
    var items by mutableStateOf<List<Item>>(emptyList())
    var favs by mutableStateOf<List<String>>(emptyList())
    var recents by mutableStateOf<List<String>>(emptyList())
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var screen by mutableStateOf<Screen>(Screen.Profiles)
    var playing by mutableStateOf<Playable?>(null)
    var zap by mutableStateOf<List<Item>>(emptyList())
    var adultOpen by mutableStateOf(false)
    var engine by mutableStateOf(sp.getInt("engine", 0))   // 0 otomatik, 1 yerleşik, 2 VLC
    var resize by mutableStateOf(sp.getInt("resize", 0))   // 0 sığdır, 1 doldur, 2 yakınlaştır
    private val history = mutableListOf<Screen>()

    val visible: List<Item> by derivedStateOf {
        val p = profile
        if (p == null) emptyList()
        else items.filter { adultOpen || !p.hideAdult || !isAdult(it.group) }
    }
    val itemsByKey: Map<String, Item> by derivedStateOf { items.associateBy { it.key } }

    // ---- gezinme ----
    fun go(s: Screen) { history.add(screen); screen = s }
    fun goRoot(s: Screen) { history.clear(); screen = s }
    fun back(): Boolean {
        if (playing != null) { playing = null; return true }
        if (history.isNotEmpty()) { screen = history.removeAt(history.lastIndex); return true }
        if (screen != Screen.Profiles) { profile = null; items = emptyList(); screen = Screen.Profiles; return true }
        return false
    }

    // ---- profiller ----
    fun saveProfile(p: Profile) {
        profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it } else profiles + p
        saveProfiles(sp, profiles)
    }
    fun deleteProfile(p: Profile) { profiles = profiles.filter { it.id != p.id }; saveProfiles(sp, profiles) }
    fun newId() = UUID.randomUUID().toString()

    suspend fun open(p: Profile) {
        loading = true; status = "Yükleniyor..."
        try {
            val list = withContext(Dispatchers.IO) {
                if (p.xtream) runCatching { loadXtream(p) }.getOrElse { parseM3u(fetch(p.m3uUrl())) }
                else parseM3u(fetch(p.m3uUrl()))
            }
            if (list.isEmpty()) status = "Kanal bulunamadı" else {
                items = list; profile = p
                favs = getList("fav_${p.id}"); recents = getList("rec_${p.id}")
                adultOpen = false; status = ""
                goRoot(Screen.Home)
            }
        } catch (e: Exception) { status = "Hata: ${e.message}" }
        loading = false
    }

    // ---- favori / son izlenen / konum ----
    private fun getList(k: String): List<String> =
        JSONArray(sp.getString(k, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun putList(k: String, l: List<String>) = sp.edit().putString(k, JSONArray(l).toString()).apply()

    fun toggleFav(key: String) {
        val p = profile ?: return
        favs = if (key in favs) favs - key else favs + key
        putList("fav_${p.id}", favs)
    }
    fun addRecent(key: String) {
        val p = profile ?: return
        recents = (listOf(key) + recents.filter { it != key }).take(40)
        putList("rec_${p.id}", recents)
    }
    fun pos(key: String): Long = sp.getLong("pos_$key", 0L)
    fun setPos(key: String, ms: Long) { if (ms > 0) sp.edit().putLong("pos_$key", ms).apply() else sp.edit().remove("pos_$key").apply() }

    // ---- ayarlar ----
    var pin: String
        get() = sp.getString("pin", "1234") ?: "1234"
        set(v) { sp.edit().putString("pin", v).apply() }
    fun setEngine(v: Int) { engine = v; sp.edit().putInt("engine", v).apply() }
    fun setResize(v: Int) { resize = v; sp.edit().putInt("resize", v).apply() }

    fun play(it: Item, openPast: Boolean = false) {
        addRecent(it.key)
        zap = if (it.type == Type.LIVE) visible.filter { x -> x.type == Type.LIVE && x.group == it.group } else emptyList()
        playing = Playable(it.url, it.name, it.streamId.ifBlank { null }, it.archive, key = it.key, openPast = openPast)
    }
}
