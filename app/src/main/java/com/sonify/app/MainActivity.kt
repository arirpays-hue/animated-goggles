package com.sonify.app

import android.Manifest
import android.os.Build
import android.os.Bundle
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
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SonifyTheme {
                    SonifyAppRoot(vm)
                }
            }
        }
    }
}

@Composable
private fun SonifyAppRoot(vm: AppViewModel) {
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Proper Android Back button handling
    BackHandler(enabled = state.showNowPlaying || state.showSettings || state.openedPlaylistId != null || state.selectedTab != MainTab.HOME) {
        when {
            state.showNowPlaying -> vm.setShowNowPlaying(false)
            state.showSettings -> vm.setShowSettings(false)
            state.openedPlaylistId != null -> vm.openPlaylist(null)
            state.selectedTab != MainTab.HOME -> vm.selectTab(MainTab.HOME)
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        vm.clearStatus()
    }

    val openedPlaylist = state.playlists.firstOrNull { it.id == state.openedPlaylistId }

    Box(modifier = Modifier.fillMaxSize().background(SpotifyBlack)) {
        // Main Content
        when {
            state.showSettings -> SettingsScreen(state, vm)
            openedPlaylist != null -> PlaylistDetailScreen(openedPlaylist, state, vm)
            else -> when (state.selectedTab) {
                MainTab.HOME -> HomeScreen(state, vm)
                MainTab.SEARCH -> SearchScreen(state, vm)
                MainTab.LIBRARY -> LibraryScreen(state, vm)
            }
        }

        // Bottom Area: MiniPlayer + Bottom Nav (hidden inside Settings)
        if (!state.showSettings) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, SpotifyBlack.copy(alpha = 0.94f), SpotifyBlack)
                        )
                    )
                    .navigationBarsPadding()
            ) {
                if (state.currentTrack != null && !state.showNowPlaying) {
                    FloatingMiniPlayer(state, vm)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavItem(
                        label = "בית",
                        icon = if (state.selectedTab == MainTab.HOME && openedPlaylist == null) Icons.Rounded.Home else Icons.Outlined.Home,
                        selected = state.selectedTab == MainTab.HOME && openedPlaylist == null,
                        onClick = { vm.selectTab(MainTab.HOME) }
                    )
                    BottomNavItem(
                        label = "חיפוש",
                        icon = if (state.selectedTab == MainTab.SEARCH && openedPlaylist == null) Icons.Rounded.Search else Icons.Outlined.Search,
                        selected = state.selectedTab == MainTab.SEARCH && openedPlaylist == null,
                        onClick = { vm.selectTab(MainTab.SEARCH) }
                    )
                    BottomNavItem(
                        label = "הספרייה שלך",
                        icon = if (state.selectedTab == MainTab.LIBRARY) Icons.Rounded.LibraryMusic else Icons.Outlined.LibraryMusic,
                        selected = state.selectedTab == MainTab.LIBRARY,
                        onClick = { vm.selectTab(MainTab.LIBRARY) }
                    )
                    BottomNavItem(
                        label = "יצירה",
                        icon = Icons.Rounded.Add,
                        selected = false,
                        onClick = { vm.setShowCreateSheet(true) }
                    )
                }
            }
        }

        // Full Now Playing Screen Overlay
        AnimatedVisibility(
            visible = state.showNowPlaying && state.currentTrack != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            NowPlayingScreen(state, vm)
        }

        // Bottom Sheets
        if (state.showCreateSheet) {
            CreateBottomSheet(state, vm)
        }
        if (state.showDevicesSheet) {
            DevicesBottomSheet(state, vm)
        }
        state.trackForOptions?.let { track ->
            TrackOptionsBottomSheet(track, state, vm)
        }

        // AI Playlist building indicator
        if (state.isBuildingAiPlaylist) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding(),
                color = SpotifyGreen,
                trackColor = SpotifyCard
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
        )
    }
}

@Composable
private fun BottomNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) SpotifyWhite else SpotifySubtle, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (selected) SpotifyWhite else SpotifySubtle, fontSize = 10.sp)
    }
}
