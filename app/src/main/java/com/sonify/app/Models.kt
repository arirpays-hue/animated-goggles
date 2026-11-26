package com.sonify.app

import java.util.UUID

enum class MainTab { HOME, SEARCH, LIBRARY }
enum class HomeFilter(val label: String) { ALL("הכל"), MUSIC("מוזיקה"), PODCASTS("פודקאסטים") }
enum class LibraryFilter(val label: String) {
    ALL("הכל"),
    PLAYLISTS("פלייליסטים"),
    OFFLINE("הורדות אופליין"),
    ARTISTS("אמנים"),
    ALBUMS("אלבומים"),
    PODCASTS("פודקאסטים")
}
enum class LibrarySort(val label: String) {
    RECENT("אחרונים"),
    ALPHA("א'-ב'"),
    CREATOR("יוצר")
}

enum class StreamSource(val label: String, val subtitle: String) {
    YOUTUBE("YouTube (ברירת מחדל)", "קטלוג מלא + תמיכה במעבר לקליפ שיר"),
    OPENSUBSONIC("שרת פרטי (OpenSubsonic / Navidrome)", "הזרמת Lossless FLAC ישירה מהשרת שלך"),
    LOCAL("קובצי המכשיר (FLAC / MP3)", "ניגון ישיר מהאחסון המקומי ללא אינטרנט")
}

enum class AudioQuality(val label: String, val kbps: Int) {
    LOW("חסכוני (24-64kbps)", 64),
    NORMAL("רגיל (128kbps)", 128),
    HIGH("גבוה (256-320kbps)", 320),
    LOSSLESS("Lossless / FLAC (מקסימלי)", 1411)
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
    val isSmartInjected: Boolean = false
)

data class ResolvedStreams(
    val audioUrl: String,
    val videoUrl: String? = null,       // Muxed video+audio or HLS
    val videoOnlyUrl: String? = null,   // Adaptive video-only to merge with audioUrl
    val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
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
    val tracks: List<Track> = emptyList(),
    val isBlend: Boolean = false,
    val blendMatchPercent: Int? = null
)

data class AppSettings(
    val streamSource: StreamSource = StreamSource.YOUTUBE,
    val quality: AudioQuality = AudioQuality.HIGH,
    val subsonicUrl: String = "",
    val subsonicUser: String = "",
    val subsonicPass: String = "",
    val geminiApiKey: String = "",
    val normalizeVolume: Boolean = true,
    val gaplessPlayback: Boolean = true,
    val crossfadeSeconds: Int = 0,
    val eqEnabled: Boolean = false,
    val eqPresetIndex: Int = 0,
    val eqBands: List<Int> = listOf(0, 0, 0, 0, 0),
    val canvasEnabled: Boolean = true,
    val autoVideoOnWifi: Boolean = false
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

data class GenreCard(
    val title: String,
    val query: String,
    val colorHex: Long
)
