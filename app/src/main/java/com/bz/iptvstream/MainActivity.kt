package com.bz.iptvstream

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val st = AppState(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, background = C.bg, surface = C.card)) {
                BackHandler { if (!st.back()) finish() }
                Box(androidx.compose.ui.Modifier.fillMaxSize().background(C.bg)) { Root(st) }
            }
        }
    }
}

@Composable
fun Root(st: AppState) {
    val pl = st.playing
    if (pl != null) { PlayerScreen(st, pl); return }
    when (val s = st.screen) {
        Screen.Profiles -> ProfilesScreen(st)
        is Screen.ProfileEdit -> ProfileEditScreen(st, s.p)
        Screen.Home -> HomeScreen(st)
        Screen.Live -> LiveScreen(st)
        Screen.Guide -> GuideScreen(st)
        is Screen.Grid -> GridScreen(st, s.type)
        is Screen.Movie -> MovieScreen(st, s.item)
        is Screen.Series -> SeriesScreen(st, s.item)
        Screen.Favs -> FavsScreen(st)
        Screen.Search -> SearchScreen(st)
        Screen.Settings -> SettingsScreen(st)
    }
}
