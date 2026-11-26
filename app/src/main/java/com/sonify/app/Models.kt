package com.sonify.app

import java.util.UUID

enum class MainTab { HOME, SEARCH, LIBRARY }
enum class AppLanguage(val label: String, val code: String) {
    HEBREW("עברית", "he"),
    ENGLISH("English", "en")
}

enum class HomeFilter(val label: String) {
    ALL("הכל"),
    MUSIC("מוזיקה"),
    PODCASTS("פודקאסטים"),
    AUDIOBOOKS("ספרי שמע")
}

enum class SearchMode(val label: String) {
    SONGS("שירים"),
    PLAYLISTS("פלייליסטים"),
    PODCASTS("פודקאסטים"),
    AUDIOBOOKS("ספרי שמע")
}

enum class LibraryFilter(val label: String) {
    ALL("הכל"),
    PLAYLISTS("פלייליסטים"),
    FOLDERS("תיקיות"),
    OFFLINE("הורדות אופליין"),
    ARTISTS("אמנים"),
    ALBUMS("אלבומים"),
    PODCASTS("פודקאסטים")
}

enum class LibrarySort(val label: String) {
    RECENT("אחרונים"),
    DATE_ADDED("תאריך הוספה"),
    ALPHA("א'-ב'"),
    CREATOR("יוצר"),
    CUSTOM("סדר מותאם")
}

enum class StreamSource(val label: String, val subtitle: String) {
    YOUTUBE("YouTube Music (ברירת מחדל)", "שירים, פלייליסטים, פודקאסטים + מעבר לקליפ"),
    OPENSUBSONIC("שרת פרטי (OpenSubsonic / Navidrome)", "הזרמת Lossless FLAC ישירה מהשרת שלך"),
    LOCAL("קובצי המכשיר (FLAC / MP3)", "ניגון ישיר מהאחסון המקומי ללא אינטרנט")
}

enum class YouTubeEngineMode(val label: String, val subtitle: String) {
    AUTO("אוטומטי (ExoPlayer + גיבוי Web)", "מנסה אודיו ישיר עם אקולייזר ועובר לגיבוי מובנה בעת חסימה"),
    WEB_DIRECT("נגן YouTube מובנה (מהיר וללא 403)", "ניגון מיידי ללא חסימות סלולר + מעבר מיידי לקליפ"),
    EXOPLAYER_ONLY("ExoPlayer בלבד", "הזרמה ישירה בלבד (אקולייזר 10 ערוצים, מונו ואיזון)")
}

enum class AudioQuality(val label: String, val kbps: Int) {
    LOW("נמוכה (24kbps)", 24),
    NORMAL("רגילה (96kbps)", 96),
    HIGH("גבוהה (160kbps)", 160),
    VERY_HIGH("גבוהה מאוד (320kbps)", 320),
    LOSSLESS("Lossless (FLAC 24-bit/44.1kHz)", 1411)
}

enum class NormalizationLevel(val label: String, val gainMb: Int) {
    QUIET("שקט", 0),
    NORMAL("רגיל", 250),
    LOUD("חזק", 550)
}

enum class RepeatMode { OFF, ALL, ONE }

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val source: StreamSource = StreamSource.YOUTUBE,
    val localUri: String? = null,
    val isPodcast: Boolean = false,
    val isAudiobook: Boolean = false,
    val isExplicit: Boolean = false,
    val isSmartInjected: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

data class RemotePlaylist(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String
)

data class CapturedMediaStream(
    val videoId: String,
    val url: String,
    val userAgent: String,
    val cookie: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
)

data class ResolvedStreams(
    val audioUrl: String,
    val videoUrl: String? = null,
    val videoOnlyUrl: String? = null,
    val userAgent: String = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    val cookie: String? = null,
    val isLossless: Boolean = false,
    val isOffline: Boolean = false,
    val codecLabel: String = "AAC"
) {
    val hasVideoClip: Boolean
        get() = !videoUrl.isNullOrBlank() || !videoOnlyUrl.isNullOrBlank()
}

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val creator: String = "שלך",
    val description: String = "",
    val coverUrl: String = "",
    val customCoverBgHex: Long? = null,
    val customCoverText: String = "",
    val customCoverSticker: String = "",
    val tracks: List<Track> = emptyList(),
    val isBlend: Boolean = false,
    val blendMatchPercent: Int? = null,
    val isRemote: Boolean = false,
    val isPublic: Boolean = true,
    val isCollaborative: Boolean = false,
    val collaborators: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val excludeFromTaste: Boolean = false,
    val isMixedMode: Boolean = false,
    val folderId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PlaylistFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

data class DeletedPlaylistRecord(
    val playlist: Playlist,
    val deletedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val sharedTrack: Track? = null,
    val trackTimestampSec: Int? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ListeningStats(
    val totalListeningMs: Long = 0L,
    val trackPlayCounts: Map<String, Int> = emptyMap(),
    val artistPlayCounts: Map<String, Int> = emptyMap()
)

data class AppSettings(
    // UI & Micro-interactions
    val language: AppLanguage = AppLanguage.HEBREW,
    val showCreateNavButton: Boolean = true,
    val showUnplayableSongs: Boolean = true,

    // Playback & Audio
    val streamSource: StreamSource = StreamSource.YOUTUBE,
    val ytEngineMode: YouTubeEngineMode = YouTubeEngineMode.AUTO,
    val wifiQuality: AudioQuality = AudioQuality.VERY_HIGH,
    val cellularQuality: AudioQuality = AudioQuality.HIGH,
    val downloadQuality: AudioQuality = AudioQuality.VERY_HIGH,
    val autoAdjustQuality: Boolean = true,
    val subsonicUrl: String = "",
    val subsonicUser: String = "",
    val subsonicPass: String = "",
    val geminiApiKey: String = "",
    val normalizeVolume: Boolean = true,
    val normalizationLevel: NormalizationLevel = NormalizationLevel.NORMAL,
    val gaplessPlayback: Boolean = true,
    val automixEnabled: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val monoAudio: Boolean = false,
    val audioBalance: Float = 0f, // -1f Left .. 0f Center .. +1f Right
    val autoplayEnabled: Boolean = true,
    val playbackSpeed: Float = 1.0f, // 0.5x .. 3.5x
    val audioOnlyPodcasts: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPresetIndex: Int = 0,
    val eqBands: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    val canvasEnabled: Boolean = true,
    val autoVideoOnWifi: Boolean = false,

    // Privacy, Data & Storage
    val dataSaver: Boolean = false,
    val downloadUsingCellular: Boolean = true,
    val offlineMode: Boolean = false,
    val allowExplicit: Boolean = true,
    val privateSession: Boolean = false,
    val shareListeningActivity: Boolean = true,
    val showRecentlyPlayedArtists: Boolean = true,
    val notifyNewReleases: Boolean = true,
    val notifyConcerts: Boolean = true,
    val notifyPodcasts: Boolean = true,
    val carModeOptimized: Boolean = false
)

data class AudioOutputItem(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val isActive: Boolean
)

data class JamState(
    val active: Boolean = false,
    val code: String = "",
    val participants: List<String> = emptyList()
)

data class BrowseCategory(
    val title: String,
    val query: String,
    val colorHex: Long,
    val group: String,
    val searchMode: SearchMode = SearchMode.SONGS
)
