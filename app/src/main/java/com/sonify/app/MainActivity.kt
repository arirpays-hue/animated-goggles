package com.sonify.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())

        setContent {
            val state by vm.uiState.collectAsState()
            val dir = if (state.settings.language == AppLanguage.HEBREW) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                SonifyTheme {
                    SonifyAppRoot(vm, state)
                }
            }
        }
    }

    // Hardware Keyboard Shortcuts (Space = Play/Pause, Ctrl+Arrows = Next/Prev/Volume)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val ctrl = event?.isCtrlPressed == true
        when {
            keyCode == KeyEvent.KEYCODE_SPACE && vm.uiState.value.currentTrack != null && !vm.uiState.value.showSettings -> {
                vm.togglePlayPause(); return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.skipNext(); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { vm.skipPrevious(); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_DPAD_UP -> { vm.audioEngine.adjustVolumeStep(true); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { vm.audioEngine.adjustVolumeStep(false); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun SonifyAppRoot(vm: AppViewModel, state: UiState) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isEn = state.settings.language == AppLanguage.ENGLISH

    BackHandler(enabled = state.showNowPlaying || state.showSettings || state.openedPlaylist != null || state.selectedTab != MainTab.HOME) {
        when {
            state.showNowPlaying -> vm.setShowNowPlaying(false)
            state.showSettings -> vm.setShowSettings(false)
            state.openedPlaylist != null -> vm.openPlaylist(null)
            state.selectedTab != MainTab.HOME -> vm.selectTab(MainTab.HOME)
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        vm.clearStatus()
    }

    val openedPlaylist = state.openedPlaylist
    val webHostedInNowPlaying = state.isUsingWebEngine && state.showNowPlaying && state.isVideoMode

    Box(modifier = Modifier.fillMaxSize().background(SpotifyBlack)) {
        // Layer 0: 260dp opaque WebView host behind app surface (passes YouTube viewport check)
        if (state.isUsingWebEngine && !webHostedInNowPlaying) {
            AndroidView(
                factory = { ctx -> FrameLayout(ctx).also { vm.audioEngine.attachWebView(it) } },
                update = { container -> vm.audioEngine.attachWebView(container) },
                modifier = Modifier.size(260.dp)
            )
        }

        // Layer 1: Main Opaque Surface
        Box(modifier = Modifier.fillMaxSize().background(SpotifyBlack)) {
            when {
                state.showSettings -> SettingsScreen(state, vm)
                openedPlaylist != null -> PlaylistDetailScreen(openedPlaylist, state, vm)
                else -> when (state.selectedTab) {
                    MainTab.HOME -> HomeScreen(state, vm)
                    MainTab.SEARCH -> SearchScreen(state, vm)
                    MainTab.LIBRARY -> LibraryScreen(state, vm)
                }
            }
        }

        if (!state.showSettings) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SpotifyBlack.copy(alpha = 0.94f), SpotifyBlack)))
                    .navigationBarsPadding()
            ) {
                if (state.currentTrack != null && !state.showNowPlaying) {
                    FloatingMiniPlayer(state, vm)
                }

                Row(modifier = Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    BottomNavItem(if (isEn) "Home" else "בית", if (state.selectedTab == MainTab.HOME && openedPlaylist == null) Icons.Rounded.Home else Icons.Outlined.Home, state.selectedTab == MainTab.HOME && openedPlaylist == null) { vm.selectTab(MainTab.HOME) }
                    BottomNavItem(if (isEn) "Search" else "חיפוש", if (state.selectedTab == MainTab.SEARCH && openedPlaylist == null) Icons.Rounded.Search else Icons.Outlined.Search, state.selectedTab == MainTab.SEARCH && openedPlaylist == null) { vm.selectTab(MainTab.SEARCH) }
                    BottomNavItem(if (isEn) "Your Library" else "הספרייה שלך", if (state.selectedTab == MainTab.LIBRARY) Icons.Rounded.LibraryMusic else Icons.Outlined.LibraryMusic, state.selectedTab == MainTab.LIBRARY) { vm.selectTab(MainTab.LIBRARY) }
                    if (state.settings.showCreateNavButton) {
                        BottomNavItem(if (isEn) "Create" else "יצירה", Icons.Rounded.Add, false) { vm.setShowCreateSheet(true) }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.showNowPlaying && state.currentTrack != null,
            enter = slideInVertically { it }, exit = slideOutVertically { it }
        ) {
            NowPlayingScreen(state, vm)
        }

        // All Modals & Sheets
        if (state.showCreateSheet) CreateBottomSheet(state, vm)
        if (state.showDevicesSheet) DevicesBottomSheet(state, vm)
        if (state.showStatsSheet) StatsWrappedSheet(state, vm)
        if (state.showHistorySheet) ListeningHistorySheet(state, vm)
        if (state.showMessagesSheet) MessagesAndFriendsSheet(state, vm)
        if (state.showImportSheet) ImportPlaylistSheet(vm)
        if (state.showRecoverSheet) RecoverPlaylistsSheet(state, vm)
        state.showCodeSheetFor?.let { (t, c) -> VisualCodeSheet(t, c) { vm.setShowCodeFor(null) } }
        state.editingCoverPlaylistId?.let { id ->
            state.playlists.firstOrNull { it.id == id }?.let { pl -> CoverArtCreatorSheet(pl, vm) }
        }
        state.trackForOptions?.let { track -> TrackOptionsBottomSheet(track, state, vm) }

        if (state.isBuildingAiPlaylist) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding(), color = SpotifyGreen, trackColor = SpotifyCard)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (state.showNowPlaying || state.showSettings) 16.dp else 132.dp, start = 16.dp, end = 16.dp)
        ) { data ->
            Snackbar(snackbarData = data, containerColor = SpotifyElevated, contentColor = SpotifyWhite, shape = RoundedCornerShape(10.dp))
        }
    }
}

@Composable
private fun BottomNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = if (selected) SpotifyWhite else SpotifySubtle, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (selected) SpotifyWhite else SpotifySubtle, fontSize = 10.sp)
    }
}
