package com.bz.iptvstream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val P_FAV = "★ Favoriler"
const val P_REC = "Son izlenenler"
const val P_ALL = "Tümü"

fun openItem(st: AppState, it: Item) {
    when {
        it.type == Type.LIVE -> st.play(it)
        it.isSeriesFolder -> { st.addRecent(it.key); st.go(Screen.Series(it)) }
        it.type == Type.MOVIE && it.vodId.isNotBlank() -> { st.addRecent(it.key); st.go(Screen.Movie(it)) }
        else -> st.play(it)
    }
}

// ---------- Canlı TV ----------
@Composable
fun LiveScreen(st: AppState) {
    val p = st.profile ?: return
    val live = remember(st.visible) { st.visible.filter { it.type == Type.LIVE } }
    val byPack = remember(live, p.groupByCountry) {
        if (p.groupByCountry) live.groupBy { countryOf(it.group).first } else live.groupBy { it.group }
    }
    val names = remember(byPack) {
        byPack.keys.sortedWith(compareBy({ if (it == "Türkiye") 0 else if (it == "Diğer") 2 else 1 }, { it }))
    }
    var pack by remember { mutableStateOf<String?>(null) }
    val curPack = pack ?: names.firstOrNull() ?: P_ALL
    val base = when (curPack) {
        P_FAV -> live.filter { it.key in st.favs }
        P_REC -> st.recents.mapNotNull { st.itemsByKey[it] }.filter { it.type == Type.LIVE }
        P_ALL -> live
        else -> byPack[curPack] ?: emptyList()
    }
    var sub by remember(curPack) { mutableStateOf<String?>(null) }
    val subs = remember(base, p.groupByCountry) {
        if (p.groupByCountry && curPack in byPack) base.map { countryOf(it.group).second }.distinct().sorted() else emptyList()
    }
    val channels = if (sub == null) base else base.filter { countryOf(it.group).second == sub }
    var sel by remember { mutableStateOf<Item?>(null) }
    val cur = sel ?: channels.firstOrNull()
    var epg by remember { mutableStateOf<List<Epg>>(emptyList()) }
    LaunchedEffect(cur?.streamId) {
        epg = emptyList()
        val id = cur?.streamId
        if (!id.isNullOrBlank() && p.xtream) epg = withContext(Dispatchers.IO) { runCatching { loadEpg(p, id, 3) }.getOrDefault(emptyList()) }
    }

    Shell(st) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(150.dp).fillMaxHeight().padding(vertical = 14.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item { Text("Canlı TV", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp)) }
                item { Text(if (p.groupByCountry) "Ülkeye göre · A–Z" else "Paketler", color = C.mute, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                val specials = listOf(P_FAV, P_REC, P_ALL)
                items(specials + names) { n ->
                    val on = n == curPack
                    Fx({ pack = n }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), if (on) C.card2 else Color.Transparent) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n, color = if (on) C.text else C.sub, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val c = when (n) { P_FAV -> live.count { it.key in st.favs }; P_REC -> 0; P_ALL -> live.size; else -> byPack[n]?.size ?: 0 }
                            if (n != P_REC) Text("$c", color = C.mute, fontSize = 10.sp)
                        }
                    }
                }
            }
            Column(Modifier.width(260.dp).fillMaxHeight().padding(vertical = 14.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (subs.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Pill("Hepsi", sub == null) { sub = null } }
                    items(subs) { s -> Pill(s, sub == s) { sub = s } }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(channels) { ch ->
                        val on = ch == cur
                        Fx({ st.play(ch) }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), if (on) C.card2 else Color.Transparent, onFocus = { if (it) sel = ch }) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(width = 36.dp, height = 28.dp).clip(RoundedCornerShape(6.dp)).background(C.card)) {
                                    AsyncImage(ch.logo.ifBlank { null }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                                Text(ch.name, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (ch.key in st.favs) Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C542), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E2A44), Color(0xFF3A5A9A))))) {
                    AsyncImage(cur?.logo?.ifBlank { null }, null, Modifier.align(Alignment.Center).size(90.dp), contentScale = ContentScale.Fit)
                    Box(Modifier.padding(10.dp).clip(RoundedCornerShape(50)).background(C.live).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text("CANLI", color = C.onLive, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(cur?.name ?: "Kanal seç", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                epg.forEachIndexed { i, e ->
                    Text((if (i == 0) "Şimdi  " else "Sonra  ") + timeOf(e.start) + "  " + e.title, color = if (i == 0) C.text else C.mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (cur != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("İzle", primary = true) { st.play(cur) }
                    Btn(if (cur.key in st.favs) "Favoriden çıkar" else "Favori") { st.toggleFav(cur.key) }
                    if (cur.archive) Btn("Geçmiş yayın") { st.play(cur, openPast = true) }
                }
            }
        }
    }
}

fun timeOf(sec: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))

// ---------- TV Rehberi ----------
@Composable
fun GuideScreen(st: AppState) {
    val p = st.profile ?: return
    val live = remember(st.visible) { st.visible.filter { it.type == Type.LIVE } }
    val byPack = remember(live, p.groupByCountry) {
        if (p.groupByCountry) live.groupBy { countryOf(it.group).first } else live.groupBy { it.group }
    }
    val names = remember(byPack) { byPack.keys.sortedWith(compareBy({ if (it == "Türkiye") 0 else if (it == "Diğer") 2 else 1 }, { it })) }
    var pack by remember { mutableStateOf<String?>(null) }
    val curPack = pack ?: names.firstOrNull()
    val chans = (byPack[curPack] ?: emptyList()).take(9)
    val guide = remember(curPack) { mutableStateMapOf<String, List<Epg>>() }
    val now = System.currentTimeMillis() / 1000
    val winStart = now - (now % 1800)
    val winEnd = winStart + 3 * 3600
    LaunchedEffect(curPack, p.xtream) {
        if (p.xtream) coroutineScope {
            chans.map { c -> async(Dispatchers.IO) { c.key to runCatching { loadEpg(p, c.streamId, 8) }.getOrDefault(emptyList()) } }
                .awaitAll().forEach { guide[it.first] = it.second }
        }
    }
    Shell(st) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TV Rehberi", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(names) { n -> Pill(n, n == curPack) { pack = n } } }
            }
            if (!p.xtream) Text("TV Rehberi sadece Xtream profillerinde çalışır.", color = C.mute, fontSize = 13.sp)
            Row {
                Spacer(Modifier.width(120.dp))
                Box(Modifier.width(720.dp)) {
                    for (h in 0..5) Text(timeOf(winStart + h * 1800L), color = C.mute, fontSize = 11.sp, modifier = Modifier.offset(x = (h * 120).dp))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chans) { c ->
                    Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp).padding(end = 8.dp))
                        Box(Modifier.width(720.dp).fillMaxHeight()) {
                            (guide[c.key] ?: emptyList()).forEach { e ->
                                val s = maxOf(e.start, winStart); val en = minOf(e.end, winEnd)
                                if (en > s) {
                                    val x = (s - winStart) / 60f * 4f
                                    val w = (en - s) / 60f * 4f - 3f
                                    val isNow = e.start <= now && e.end > now
                                    Fx({ st.play(c) }, Modifier.offset(x = x.dp).width(w.dp).fillMaxHeight(), RoundedCornerShape(8.dp), if (isNow) C.card2 else C.card) {
                                        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                            Text(e.title, color = C.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(timeOf(e.start) + " – " + timeOf(e.end), color = C.mute, fontSize = 9.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Film / dizi ızgarası ----------
@Composable
fun GridScreen(st: AppState, type: Type) {
    val list = remember(st.visible, type) { st.visible.filter { it.type == type } }
    val cats = remember(list) { list.map { it.group }.distinct().sorted() }
    var cat by remember(type) { mutableStateOf(P_ALL) }
    var az by remember(type) { mutableStateOf(false) }
    val base = when (cat) {
        P_ALL -> list
        P_FAV -> list.filter { it.key in st.favs }
        P_REC -> st.recents.mapNotNull { st.itemsByKey[it] }.filter { it.type == type }
        else -> list.filter { it.group == cat }
    }
    val shown = if (az) base.sortedBy { it.name.lowercase() } else base.asReversed()
    Shell(st) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(type.label, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${list.size} içerik", color = C.mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Pill(if (az) "Sırala: A–Z" else "Sırala: Yeni eklenen") { az = !az }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(P_ALL, P_FAV, P_REC) + cats) { c -> Pill(c, c == cat) { cat = c } }
            }
            LazyVerticalGrid(GridCells.Adaptive(100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                gridItems(shown) { it -> Poster(it, it.key in st.favs) { openItem(st, it) } }
            }
        }
    }
}

// ---------- Film detayı ----------
@Composable
fun MovieScreen(st: AppState, item: Item) {
    val p = st.profile ?: return
    var info by remember { mutableStateOf<VodInfo?>(null) }
    LaunchedEffect(item.key) {
        if (p.xtream && item.vodId.isNotBlank()) info = withContext(Dispatchers.IO) { runCatching { loadVodInfo(p, item.vodId) }.getOrNull() }
    }
    val pos = st.pos(item.key)
    fun start(fromStart: Boolean) {
        if (fromStart) st.setPos(item.key, 0)
        st.zap = emptyList()
        st.playing = Playable(item.url, item.name, null, false, key = item.key)
    }
    Shell(st) {
        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Fx({ st.back() }, Modifier.size(36.dp), RoundedCornerShape(50), C.card) { Icon(Icons.Filled.ArrowBack, "Geri", tint = C.text, modifier = Modifier.align(Alignment.Center).size(20.dp)) }
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(C.card)) {
                    AsyncImage(item.logo.ifBlank { null }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.name, color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.group, color = C.mute, fontSize = 13.sp)
                val i = info
                if (i != null) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(i.meta) { (k, v) -> Column { Text(k, color = C.mute, fontSize = 10.sp); Text(v, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) } }
                    }
                    if (i.plot.isNotBlank()) Text(i.plot, color = C.sub, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                } else if (p.xtream && item.vodId.isNotBlank()) Text("Yükleniyor...", color = C.mute, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pos > 0) { Btn("Devam et · ${fmtTime(pos)}", primary = true) { start(false) }; Btn("Baştan oynat") { start(true) } }
                    else Btn("Oynat", primary = true) { start(false) }
                    Btn(if (item.key in st.favs) "Favoriden çıkar" else "Favorilere ekle") { st.toggleFav(item.key) }
                }
            }
        }
    }
}

// ---------- Dizi detayı ----------
@Composable
fun SeriesScreen(st: AppState, item: Item) {
    val p = st.profile ?: return
    var eps by remember { mutableStateOf(item.eps) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(item.key) {
        if (eps.isEmpty() && item.seriesId.isNotBlank())
            try { eps = withContext(Dispatchers.IO) { loadEps(p, item.seriesId) } } catch (e: Exception) { err = e.message ?: "Hata" }
    }
    val seasons = eps.map { it.season }.distinct().sorted()
    var sel by remember(seasons) { mutableStateOf(seasons.firstOrNull() ?: 1) }
    Shell(st) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Fx({ st.back() }, Modifier.size(36.dp), RoundedCornerShape(50), C.card) { Icon(Icons.Filled.ArrowBack, "Geri", tint = C.text, modifier = Modifier.align(Alignment.Center).size(20.dp)) }
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(C.card)) {
                    AsyncImage(item.logo.ifBlank { null }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(item.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Btn(if (item.key in st.favs) "Favoriden çıkar" else "Favori") { st.toggleFav(item.key) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (eps.isEmpty()) Text(if (err.isEmpty()) "Yükleniyor..." else err, color = C.mute, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(seasons) { s -> Pill("Sezon $s", s == sel) { sel = s } } }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(eps.filter { it.season == sel }) { e ->
                        val pos = st.pos(e.url)
                        Fx({
                            st.zap = emptyList()
                            st.playing = Playable(e.url, "${item.name} S${e.season}E${e.num}", null, false, key = e.url)
                        }, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), C.card) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${e.num}", color = C.mute, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                                Text(e.title.ifBlank { "Bölüm ${e.num}" }, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (pos > 0) Text("${fmtTime(pos)} izlendi", color = C.accent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Favoriler ----------
@Composable
fun FavsScreen(st: AppState) {
    val favs = remember(st.favs, st.items) { st.favs.mapNotNull { st.itemsByKey[it] } }
    Shell(st) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Favoriler", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (favs.isEmpty()) Text("Henüz favori yok. Bir içeriğin detayından ya da kanal listesinden ekleyebilirsin.", color = C.mute, fontSize = 13.sp)
            LazyVerticalGrid(GridCells.Adaptive(100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                gridItems(favs) { it -> Poster(it, false) { openItem(st, it) } }
            }
        }
    }
}

// ---------- Arama ----------
@Composable
fun SearchScreen(st: AppState) {
    var q by remember { mutableStateOf("") }
    val res = remember(q, st.visible) {
        if (q.length < 2) emptyList() else st.visible.filter { it.name.contains(q, ignoreCase = true) }.take(150)
    }
    Shell(st) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Kanal, film veya dizi ara") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(res) { it ->
                    Fx({ openItem(st, it) }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), C.card) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(it.type.label, color = C.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                            Text(it.name, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(it.group, color = C.mute, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------- Ayarlar ----------
@Composable
fun SettingsScreen(st: AppState) {
    val p = st.profile ?: return
    val scope = rememberCoroutineScope()
    val secs = listOf("Hesap bilgisi", "Oynatıcı", "Ebeveyn kontrolü", "Liste", "Hakkında")
    var sec by remember { mutableStateOf(0) }
    var acc by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pinAsk by remember { mutableStateOf(false) }
    var pinChg by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(p.id) {
        if (p.xtream) acc = withContext(Dispatchers.IO) { runCatching { loadAccount(p) }.getOrDefault(listOf("Durum" to "Alınamadı")) }
    }
    if (pinAsk) AlertDialog(onDismissRequest = { pinAsk = false; pin = "" }, title = { Text("Ebeveyn PIN") },
        text = { OutlinedTextField(pin, { pin = it }, singleLine = true, label = { Text("PIN (varsayılan 1234)") }) },
        confirmButton = { TextButton({ if (pin == st.pin) st.adultOpen = true; pinAsk = false; pin = "" }) { Text("Aç") } })
    if (pinChg) AlertDialog(onDismissRequest = { pinChg = false; pin = "" }, title = { Text("Yeni PIN") },
        text = { OutlinedTextField(pin, { pin = it }, singleLine = true, label = { Text("En az 4 hane") }) },
        confirmButton = { TextButton({ if (pin.length >= 4) st.pin = pin; pinChg = false; pin = "" }) { Text("Kaydet") } })

    Shell(st) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(190.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ayarlar", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                secs.forEachIndexed { i, s ->
                    Fx({ sec = i }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), if (i == sec) C.card2 else Color.Transparent) {
                        Text(s, color = if (i == sec) C.text else C.sub, fontSize = 13.sp, fontWeight = if (i == sec) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (sec) {
                    0 -> {
                        Text("Hesap bilgisi", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                (listOf("Profil" to p.name, "Canlı kanal" to st.items.count { it.type == Type.LIVE }.toString(),
                                    "Film / Dizi" to "${st.items.count { it.type == Type.MOVIE }} / ${st.items.count { it.type == Type.SERIES }}") + acc).forEach { (k, v) ->
                                    Row { Text(k, color = C.mute, fontSize = 13.sp, modifier = Modifier.width(120.dp)); Text(v, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                        }
                    }
                    1 -> {
                        Text("Oynatıcı", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Oynatıcı motoru (TS, MKV, MP4, HLS dahil tüm formatlar)", color = C.mute, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Otomatik", "Yerleşik", "VLC").forEachIndexed { i, n -> Pill(n, st.engine == i) { st.setEngine(i) } }
                        }
                        Text("Görüntü oranı", color = C.mute, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Sığdır", "Doldur", "Yakınlaştır").forEachIndexed { i, n -> Pill(n, st.resize == i) { st.setResize(i) } }
                        }
                        Text("Otomatik: önce yerleşik oynatıcı denenir, açılmayan yayında VLC devreye girer.", color = C.mute, fontSize = 12.sp)
                    }
                    2 -> {
                        Text("Ebeveyn kontrolü", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(if (st.adultOpen) "+18 paketleri açık" else "+18 paketleri gizli", color = C.sub, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn(if (st.adultOpen) "Kilitle" else "PIN ile aç", primary = true) { if (st.adultOpen) st.adultOpen = false else pinAsk = true }
                            Btn("PIN değiştir") { pinChg = true }
                        }
                    }
                    3 -> {
                        Text("Liste", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Kanallar ${if (p.groupByCountry) "ülkeye göre" else "pakete göre"} gruplanıyor. Bu ayarı profil düzenleme ekranından değiştirebilirsin.", color = C.mute, fontSize = 12.sp)
                        Btn("Listeyi yenile") { scope.launch { st.open(p) } }
                    }
                    else -> {
                        Text("Hakkında", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("BZİPTVSTREAM 1.0", color = C.sub, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
