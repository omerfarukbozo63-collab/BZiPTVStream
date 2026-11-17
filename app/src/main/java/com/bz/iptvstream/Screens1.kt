package com.bz.iptvstream

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Ortak parçalar ----------
@Composable
fun Shell(st: AppState, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().background(C.bg)) {
        Rail(st)
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
fun Rail(st: AppState) {
    val entries: List<Triple<ImageVector, String, Screen>> = listOf(
        Triple(Icons.Filled.Home, "Ana Ekran", Screen.Home),
        Triple(Icons.Filled.LiveTv, "Canlı TV", Screen.Live),
        Triple(Icons.Filled.DateRange, "TV Rehberi", Screen.Guide),
        Triple(Icons.Filled.Movie, "Filmler", Screen.Grid(Type.MOVIE)),
        Triple(Icons.Filled.VideoLibrary, "Diziler", Screen.Grid(Type.SERIES)),
        Triple(Icons.Filled.Star, "Favoriler", Screen.Favs),
        Triple(Icons.Filled.Search, "Ara", Screen.Search),
        Triple(Icons.Filled.Settings, "Ayarlar", Screen.Settings)
    )
    Column(
        Modifier.width(54.dp).fillMaxHeight().background(C.rail).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(painterResource(R.drawable.logo_round), null, Modifier.size(34.dp).padding(bottom = 2.dp))
        Spacer(Modifier.height(6.dp))
        entries.forEach { (icon, label, scr) ->
            val sel = st.screen == scr
            Fx({ st.goRoot(scr) }, Modifier.size(40.dp), RoundedCornerShape(12.dp), if (sel) C.accent else Color.Transparent) {
                Icon(icon, label, tint = if (sel) C.onAccent else C.sub, modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
        }
    }
}

@Composable
fun Poster(item: Item, fav: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Fx(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(8.dp), C.card) {
            AsyncImage(item.logo.ifBlank { null }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (fav) Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C542), modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp))
        }
        Text(item.name, color = C.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
    }
}

@Composable
fun ChannelCard(item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Fx(onClick, modifier.width(140.dp), RoundedCornerShape(12.dp), C.card) {
        Column {
            Box(Modifier.fillMaxWidth().height(70.dp).background(Brush.linearGradient(listOf(Color(0xFF1E2A44), Color(0xFF3A5A9A))))) {
                AsyncImage(item.logo.ifBlank { null }, null, Modifier.align(Alignment.Center).size(48.dp), contentScale = ContentScale.Fit)
                Box(Modifier.padding(6.dp).clip(RoundedCornerShape(50)).background(C.live).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("CANLI", color = C.onLive, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(item.name, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
    }
}

// ---------- Profiller ----------
@Composable
fun ProfilesScreen(st: AppState) {
    val scope = rememberCoroutineScope()
    val brushes = listOf(
        listOf(Color(0xFFE9E32B), Color(0xFF5A5608)), listOf(Color(0xFF1FB5A0), Color(0xFF0B3A34)),
        listOf(Color(0xFF4C8DFF), Color(0xFF102A5A)), listOf(Color(0xFFB07CFF), Color(0xFF2A1A55))
    )
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2438), C.bg))),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.logo_round), null, Modifier.size(46.dp))
            Text("BZİPTVSTREAM", color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text("Kim izliyor?", color = C.mute, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 22.dp))
        if (st.status.isNotEmpty()) Text(st.status, color = if (st.loading) C.accent else Color(0xFFFF8A94), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 40.dp)) {
            items(st.profiles) { p ->
                val idx = (p.name.hashCode() and 0x7fffffff) % brushes.size
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fx({ scope.launch { st.open(p) } }, Modifier.width(150.dp), RoundedCornerShape(18.dp), C.card) {
                        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(68.dp).clip(CircleShape).background(Brush.linearGradient(brushes[idx])), contentAlignment = Alignment.Center) {
                                Text(p.name.take(1).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(p.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.xtream) "Xtream · ${p.user}" else "M3U linki", color = C.mute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Btn("Düzenle") { st.go(Screen.ProfileEdit(p)) }
                }
            }
            item {
                Fx({ st.go(Screen.ProfileEdit(null)) }, Modifier.width(150.dp).height(170.dp), RoundedCornerShape(18.dp), C.card) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Add, null, tint = C.sub, modifier = Modifier.size(40.dp))
                        Text("Yeni profil", color = C.sub, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ---------- Profil düzenle ----------
@Composable
fun ProfileEditScreen(st: AppState, existing: Profile?) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.pass ?: "") }
    var xtream by remember { mutableStateOf(existing?.xtream ?: true) }
    var fmt by remember { mutableStateOf(existing?.fmt ?: "ts") }
    var group by remember { mutableStateOf(existing?.groupByCountry ?: true) }
    var hide by remember { mutableStateOf(existing?.hideAdult ?: true) }
    var info by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var err by remember { mutableStateOf("") }

    fun build() = Profile(existing?.id ?: st.newId(), name.ifBlank { "Profil" }, url.trim(),
        if (xtream) user.trim() else "", if (xtream) pass.trim() else "", fmt, group, hide)

    Row(Modifier.fillMaxSize().background(C.bg).padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Fx({ st.back() }, Modifier.size(38.dp), CircleShape, C.card) { Icon(Icons.Filled.ArrowBack, "Geri", tint = C.text, modifier = Modifier.align(Alignment.Center)) }
                Text(if (existing == null) "Yeni profil" else "Profili düzenle", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Xtream Codes", xtream) { xtream = true }
                Pill("M3U linki", !xtream) { xtream = false }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Profil adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text(if (xtream) "Sunucu adresi (http://host:port)" else "M3U linki") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (xtream) {
                OutlinedTextField(user, { user = it }, label = { Text("Kullanıcı adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Şifre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Canlı yayın formatı", color = C.mute, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("TS", fmt == "ts") { fmt = "ts" }
                    Pill("M3U8 (HLS)", fmt == "m3u8") { fmt = "m3u8" }
                }
            }
        }
        Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val r = info
                    if (r == null && err.isEmpty()) Text("Bağlantıyı test etmek için aşağıdaki butonu kullan.", color = C.mute, fontSize = 12.sp)
                    if (err.isNotEmpty()) Text(err, color = Color(0xFFFF8A94), fontSize = 12.sp)
                    if (r != null) {
                        Text("Bağlantı başarılı", color = Color(0xFF3DDC84), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        r.forEach { (k, v) ->
                            Row { Text(k, color = C.mute, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(v, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ülkeye göre grupla", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(group, { group = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+18 paketlerini gizle", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(hide, { hide = it })
            }
            Btn("Kaydet", primary = true, modifier = Modifier.fillMaxWidth()) {
                if (url.isNotBlank()) { st.saveProfile(build()); st.back() }
            }
            Btn("Bağlantıyı test et", modifier = Modifier.fillMaxWidth()) {
                info = null; err = ""
                val p = build()
                scope.launch {
                    try {
                        info = withContext(Dispatchers.IO) {
                            if (p.xtream) loadAccount(p) else listOf("Satır sayısı" to fetch(p.m3uUrl()).lines().size.toString())
                        }
                    } catch (e: Exception) { err = "Hata: ${e.message}" }
                }
            }
            if (existing != null) Btn("Sil", danger = true, modifier = Modifier.fillMaxWidth()) { st.deleteProfile(existing); st.back() }
        }
    }
}

// ---------- Ana ekran ----------
@Composable
fun HomeScreen(st: AppState) {
    val p = st.profile ?: return
    val live = remember(st.visible) { st.visible.filter { it.type == Type.LIVE } }
    val movies = remember(st.visible) { st.visible.filter { it.type == Type.MOVIE } }
    val recentLive = st.recents.mapNotNull { st.itemsByKey[it] }.filter { it.type == Type.LIVE }.take(10)
    val resume = st.recents.mapNotNull { st.itemsByKey[it] }.firstOrNull { it.type != Type.LIVE }
    val chRow = if (recentLive.isNotEmpty()) recentLive else live.take(10)

    Shell(st) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hoş geldin, ${p.name}", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill("Ara") { st.goRoot(Screen.Search) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Fx({ resume?.let { if (it.isSeriesFolder) st.go(Screen.Series(it)) else st.go(Screen.Movie(it)) } },
                        Modifier.weight(1.4f).fillMaxHeight(), RoundedCornerShape(16.dp), C.card2) {
                        if (resume != null) AsyncImage(resume.logo.ifBlank { null }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
                        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (resume != null) "KALDIĞIN YERDEN DEVAM" else "BAŞLA", color = C.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(resume?.name ?: "Bir film, dizi veya kanal seç", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (resume != null && st.pos(resume.key) > 0) Text("${fmtTime(st.pos(resume.key))} izlendi", color = C.sub, fontSize = 12.sp)
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Fx({ st.goRoot(Screen.Live) }, Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(12.dp), C.card) { Text("Canlı TV", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) }
                        Fx({ st.goRoot(Screen.Guide) }, Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(12.dp), C.card) { Text("TV Rehberi", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) }
                        Fx({ st.back(); st.back() }, Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(12.dp), C.card) { Text("Profil değiştir", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) }
                    }
                }
            }
            if (chRow.isNotEmpty()) {
                item { Text(if (recentLive.isNotEmpty()) "Son izlenen kanallar" else "Canlı kanallar", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(chRow) { c -> ChannelCard(c) { st.play(c) } } } }
            }
            if (movies.isNotEmpty()) {
                item { Text("Yeni eklenen filmler", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(movies.takeLast(20).reversed()) { m -> Poster(m, m.key in st.favs, Modifier.width(100.dp)) { st.addRecent(m.key); st.go(Screen.Movie(m)) } }
                    }
                }
            }
        }
    }
}
