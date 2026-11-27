package com.sonify.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissions()

        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.uiState.collectAsState()
            SonifyRootApp(state = state, vm = vm)
        }
    }

    private fun requestStartupPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
    }
}

@Composable
fun SonifyRootApp(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        vm.clearStatus()
    }

    BackHandler(
        enabled = state.showDjStudioSheet || state.showNowPlaying || state.showSettings || state.openedArtist != null || state.openedPlaylist != null || state.selectedFolderId != null || state.selectedTab != MainTab.HOME
    ) {
        when {
            state.showDjStudioSheet -> vm.setShowDjStudioSheet(false)
            state.showNowPlaying -> vm.setShowNowPlaying(false)
            state.showSettings -> {
                if (state.settingsSection != SettingsSection.ROOT) vm.setSettingsSection(SettingsSection.ROOT)
                else vm.setShowSettings(false)
            }
            state.openedArtist != null -> vm.closeArtistPage()
            state.openedPlaylist != null -> vm.openPlaylist(null)
            state.selectedFolderId != null -> vm.selectFolder(null)
            state.selectedTab != MainTab.HOME -> vm.selectTab(MainTab.HOME)
        }
    }

    val darkColors = darkColorScheme(
        primary = SpotifyGreen,
        onPrimary = Color.Black,
        background = SpotifyBlack,
        surface = SpotifyDarkSurface,
        onBackground = SpotifyWhite,
        onSurface = SpotifyWhite
    )

    CompositionLocalProvider(LocalLayoutDirection provides if (isEn) LayoutDirection.Ltr else LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColors) {
            Box(modifier = Modifier.fillMaxSize().background(SpotifyBlack)) {

                // Keeps WebPlaybackEngine permanently attached for seamless audio & screen-off playback
                if (!(state.showNowPlaying && state.isVideoMode)) {
                    AndroidView(
                        factory = { ctx -> FrameLayout(ctx).also { vm.audioEngine.attachWebView(it) } },
                        update = { vm.audioEngine.attachWebView(it) },
                        modifier = Modifier.size(1.dp).alpha(0.01f)
                    )
                }

                // Main Content Layer
                when {
                    state.showSettings -> SettingsScreen(state, vm)
                    state.openedArtist != null -> ArtistPageScreen(state.openedArtist, state, vm)
                    state.openedPlaylist != null -> PlaylistDetailScreen(state.openedPlaylist, state, vm)
                    else -> when (state.selectedTab) {
                        MainTab.HOME -> HomeScreen(state, vm)
                        MainTab.SEARCH -> SearchScreen(state, vm)
                        MainTab.LIBRARY -> LibraryScreen(state, vm)
                    }
                }

                // Bottom Overlay: Floating Mini-Player + Spotify Custom Navigation Bar
                if (!state.showSettings && !state.showNowPlaying) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.35f to SpotifyBlack.copy(alpha = 0.92f),
                                    1f to SpotifyBlack
                                )
                            )
                            .navigationBarsPadding()
                    ) {
                        if (state.currentTrack != null) {
                            FloatingMiniPlayer(state = state, vm = vm)
                        }
                        SpotifyBottomNavBar(state = state, vm = vm)
                    }
                }

                // Smooth Spotify Slide-Up Now Playing Screen
                AnimatedVisibility(
                    visible = state.showNowPlaying && state.currentTrack != null,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                ) {
                    NowPlayingScreen(state = state, vm = vm)
                }

                // Global Modals & Sheets
                if (state.showDevicesSheet) DevicesBottomSheet(state, vm)
                if (state.showCreateSheet) CreateHubSheet(state, vm)
                if (state.showStatsSheet) StatsWrappedSheet(state, vm)
                if (state.showHistorySheet) ListeningHistorySheet(state, vm)
                if (state.showMessagesSheet) MessagesAndFriendsSheet(state, vm)
                if (state.showImportSheet) ImportPlaylistSheet(vm, str)
                if (state.showRecoverSheet) RecoverPlaylistsSheet(state, vm)
                state.trackForOptions?.let { trk -> TrackOptionsBottomSheet(trk, state, vm) }
                state.showCodeSheetFor?.let { (title, code) ->
                    VisualCodeSheet(title = title, codeData = code, vm = vm, str = str, onDismiss = { vm.setShowCodeFor(null) })
                }
                state.editingCoverPlaylistId?.let { plId ->
                    state.playlists.firstOrNull { it.id == plId }?.let { pl -> CoverArtCreatorSheet(pl, vm, str) }
                }

                // Dedicated AI DJ Studio Overlay rendered at top Z-index (always opens above Home, Create & Now Playing!)
                AnimatedVisibility(
                    visible = state.showDjStudioSheet,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                ) {
                    AiDjStudioOverlay(state = state, vm = vm)
                }

                if (state.isBuildingAiPlaylist) {
                    Card(
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
                        shape = RoundedCornerShape(50),
                        colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(str.t("ה-AI בונה את הפלייליסט שלך...", "AI is building your playlist..."), color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 132.dp)
                )
            }
        }
    }
}

@Composable
private fun SpotifyBottomNavBar(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isTopLevel = state.openedPlaylist == null && state.openedArtist == null

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItemBox(
            label = str.home,
            selected = isTopLevel && state.selectedTab == MainTab.HOME,
            onClick = { vm.selectTab(MainTab.HOME) }
        ) { sel, tint -> SpotifyNavHomeIcon(sel, tint, size = 23.dp) }

        NavItemBox(
            label = str.search,
            selected = isTopLevel && state.selectedTab == MainTab.SEARCH,
            onClick = { vm.selectTab(MainTab.SEARCH) }
        ) { sel, tint -> SpotifyNavSearchIcon(sel, tint, size = 23.dp) }

        NavItemBox(
            label = str.yourLibrary,
            selected = isTopLevel && state.selectedTab == MainTab.LIBRARY,
            onClick = { vm.selectTab(MainTab.LIBRARY) }
        ) { sel, tint -> SpotifyNavLibraryIcon(sel, tint, size = 23.dp) }

        if (state.settings.showCreateNavButton) {
            NavItemBox(
                label = str.create,
                selected = state.showCreateSheet,
                onClick = { vm.setShowCreateSheet(true) }
            ) { _, tint -> SpotifyNavCreateIcon(tint, size = 23.dp) }
        }
    }
}

@Composable
private fun NavItemBox(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Boolean, Color) -> Unit
) {
    val tint = if (selected) SpotifyWhite else SpotifySubtle
    Column(
        modifier = Modifier.spotifyPressClickable(pressedScale = 0.90f, onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon(selected, tint)
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
