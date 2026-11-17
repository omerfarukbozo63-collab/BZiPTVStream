package com.bz.iptvstream

import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

const val UA = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** Motorlar için ortak arayüz. */
class Eng(
    val pos: () -> Long, val dur: () -> Long, val playing: () -> Boolean,
    val toggle: () -> Unit, val seek: (Long) -> Unit, val release: () -> Unit
)

@Composable
fun PlayerScreen(st: AppState, pl: Playable) {
    val p = st.profile
    val isLive = st.itemsByKey[pl.key]?.type == Type.LIVE
    var useVlc by remember(pl.url) { mutableStateOf(st.engine == 2) }
    var err by remember(pl.url) { mutableStateOf("") }
    var eng by remember(pl.url, useVlc) { mutableStateOf<Eng?>(null) }
    var hud by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var hudTick by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var past by remember { mutableStateOf<List<Past>>(emptyList()) }
    var cur by remember(pl.url) { mutableStateOf(pl) }
    val focus = remember { FocusRequester() }
    val ctx = LocalContext.current

    fun fail(msg: String) {
        if (!useVlc && st.engine != 1) { useVlc = true; err = "" }   // otomatik: VLC'ye geç
        else err = msg
    }

    // oynatmayı başlat / yeni kanal
    LaunchedEffect(cur.url) { hud = true; hudTick++ }
    LaunchedEffect(hudTick) { delay(4500); if (!panel) hud = false }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(panel) { if (!panel) runCatching { focus.requestFocus() } }

    // konum takibi + kaydetme (kaldığın yerden devam)
    LaunchedEffect(eng) {
        var n = 0
        while (true) {
            eng?.let { e ->
                pos = e.pos(); dur = e.dur(); paused = !e.playing()
                if (!isLive && ++n % 5 == 0 && pos > 5000 && (dur <= 0 || pos < dur - 15000)) st.setPos(cur.key, pos)
            }
            delay(1000)
        }
    }
    DisposableEffect(cur.url) {
        onDispose {
            val e = eng
            if (!isLive && e != null) {
                val ps = e.pos(); val d = e.dur()
                if (d > 0 && ps > d - 15000) st.setPos(cur.key, 0) else if (ps > 5000) st.setPos(cur.key, ps)
            }
        }
    }
    DisposableEffect(cur.url, useVlc) { onDispose { eng?.release() } }

    fun zapBy(d: Int) {
        val l = st.zap
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.key == cur.key }
        val n = l[(i + d + l.size) % l.size]
        st.addRecent(n.key)
        cur = Playable(n.url, n.name, n.streamId.ifBlank { null }, n.archive, key = n.key)
        st.playing = cur
    }

    if (panel) BackHandler { panel = false }
    LaunchedEffect(panel) {
        if (panel && isLive && cur.archive && cur.streamId != null && p != null && p.xtream && past.isEmpty())
            past = withContext(Dispatchers.IO) { runCatching { loadPast(p, cur.streamId!!) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(cur.url, pl.openPast) { if (pl.openPast) panel = true }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusTarget()
            .onPreviewKeyEvent { ev ->
                if (panel || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val e = eng
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (!hud) { hud = true; hudTick++ } else if (!isLive) e?.toggle() else { panel = true }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                        { if (!isLive) e?.seek(e.pos() + 15000); hud = true; hudTick++; true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                        { if (!isLive) e?.seek((e.pos() - 15000).coerceAtLeast(0)); hud = true; hudTick++; true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (isLive) zapBy(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (isLive) zapBy(1); true }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { panel = true; true }
                    else -> false
                }
            }
    ) {
        val url = cur.url
        key(url, useVlc) {
            val resumeAt = if (isLive) 0L else st.pos(cur.key)
            if (useVlc) VlcSurface(url, st.resize, resumeAt, { eng = it }, { fail(it) })
            else ExoSurface(url, st.resize, resumeAt, { eng = it }, { fail(it) })
        }

        if (err.isNotEmpty()) Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yayın açılamadı", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(err, color = C.mute, fontSize = 12.sp)
            Text("Menü tuşu ile oynatıcıyı değiştirebilirsin", color = C.sub, fontSize = 12.sp)
        }

        if (hud && !panel) {
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isLive) Box(Modifier.clip(RoundedCornerShape(50)).background(C.live).padding(horizontal = 9.dp, vertical = 3.dp)) { Text("CANLI", color = C.onLive, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Text(cur.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (useVlc) "VLC" else "Yerleşik", color = C.mute, fontSize = 11.sp)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isLive && dur > 0) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF))) {
                        Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(C.accent))
                    }
                    Row { Text(fmtTime(pos), color = Color.White, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(fmtTime(dur), color = C.mute, fontSize = 12.sp) }
                }
                Text(when {
                    isLive -> "↑↓ kanal değiştir   OK/Menü: ayarlar"
                    paused -> "⏸ Duraklatıldı   ◀ ▶ 15 sn   OK: oynat"
                    else -> "◀ ▶ 15 sn   OK: duraklat   Menü: ayarlar"
                }, color = C.sub, fontSize = 11.sp)
            }
        }

        if (panel) Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp).background(Color(0xEE0B0E14)).padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Oynatıcı ayarları", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Motor", color = C.mute, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Yerleşik", !useVlc) { st.setEngine(1); useVlc = false }
                    Pill("VLC", useVlc) { st.setEngine(2); useVlc = true }
                }
                Text("Görüntü", color = C.mute, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Sığdır", "Doldur", "Zoom").forEachIndexed { i, n -> Pill(n, st.resize == i) { st.setResize(i); eng?.let { } } }
                }
                if (isLive && cur.archive && cur.streamId != null) {
                    Text("Geçmiş yayınlar", color = C.mute, fontSize = 11.sp)
                    if (past.isEmpty()) Text("Yükleniyor / kayıt yok", color = C.mute, fontSize = 11.sp)
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(past) { pa ->
                            Fx({
                                val pr = st.profile ?: return@Fx
                                val ext = if (pr.fmt == "ts") "ts" else "m3u8"
                                val u = "${pr.base()}/timeshift/${pr.user}/${pr.pass}/${pa.minutes}/${pa.start}/${cur.streamId}.$ext"
                                st.zap = emptyList()
                                cur = Playable(u, "${cur.title} · ${pa.title}", null, false, key = u)
                                st.playing = cur; panel = false
                            }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), C.card) {
                                Text(pa.title + "  (" + pa.start.takeLast(5).replace('-', ':') + ")", color = C.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }
                Btn("Kapat") { panel = false }
            }
        }
    }
}

@Composable
fun ExoSurface(url: String, resize: Int, resumeAt: Long, onEng: (Eng) -> Unit, onFail: (String) -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
            .build()
    }
    DisposableEffect(player) {
        val mi = MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        var seeked = false
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onFail(error.errorCodeName) }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY && !seeked) { seeked = true; if (resumeAt > 0) player.seekTo(resumeAt) } }
        }
        player.addListener(l); player.setMediaItem(mi); player.prepare(); player.playWhenReady = true
        onEng(Eng({ player.currentPosition }, { player.duration.coerceAtLeast(0) }, { player.isPlaying },
            { if (player.isPlaying) player.pause() else player.play() }, { player.seekTo(it) }, { }))
        onDispose { player.removeListener(l); player.release() }
    }
    AndroidView(factory = { c ->
        PlayerView(c).apply { useController = false; this.player = player; layoutParams = ViewGroup.LayoutParams(-1, -1); keepScreenOn = true }
    }, update = {
        it.resizeMode = when (resize) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL; 2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun VlcSurface(url: String, resize: Int, resumeAt: Long, onEng: (Eng) -> Unit, onFail: (String) -> Unit) {
    val ctx = LocalContext.current
    val lib = remember { LibVLC(ctx, arrayListOf("--network-caching=2000", "--http-user-agent=$UA", "--http-reconnect")) }
    val mp = remember { MediaPlayer(lib) }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    DisposableEffect(Unit) {
        var seeked = false
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.EncounteredError -> onFail("VLC hata")
                MediaPlayer.Event.Playing -> if (!seeked) { seeked = true; if (resumeAt > 0) mp.time = resumeAt }
            }
        }
        val m = Media(lib, Uri.parse(url)); m.setHWDecoderEnabled(true, false)
        mp.media = m; m.release()
        onEng(Eng({ mp.time.coerceAtLeast(0) }, { mp.length.coerceAtLeast(0) }, { mp.isPlaying },
            { if (mp.isPlaying) mp.pause() else mp.play() }, { mp.time = it }, { }))
        onDispose {
            runCatching { mp.setEventListener(null); mp.stop(); mp.detachViews(); mp.release(); lib.release() }
        }
    }
    AndroidView(factory = { c ->
        VLCVideoLayout(c).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1); keepScreenOn = true
            mp.attachViews(this, null, false, false); layout = this; mp.play()
        }
    }, update = {
        mp.videoScale = when (resize) { 1 -> MediaPlayer.ScaleType.SURFACE_FILL; 2 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN; else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT }
    }, modifier = Modifier.fillMaxSize())
}
