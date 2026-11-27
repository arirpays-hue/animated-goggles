package com.sonify.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun upgradeArtworkUrlGlobal(url: String): String {
    if (url.isBlank()) return ""
    if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
        return when {
            Regex("=w\\d+-h\\d+").containsMatchIn(url) ->
                url.replace(Regex("=w\\d+-h\\d+.*"), "=w1080-h1080-l95-rj")
            Regex("=s\\d+").containsMatchIn(url) ->
                url.replace(Regex("=s\\d+.*"), "=s1080-c-k-c0x00ffffff-no-rj")
            else -> url
        }
    }
    if (url.contains("i.ytimg.com/vi/")) {
        val clean = url.substringBefore("?")
        val vid = clean.substringAfter("i.ytimg.com/vi/").substringBefore("/")
        if (vid.length == 11) return "https://i.ytimg.com/vi/$vid/maxresdefault.jpg"
    }
    return url
}

enum class MainTab { HOME, SEARCH, LIBRARY }
enum class AppLanguage(val label: String, val code: String) {
    HEBREW("עברית", "he"),
    ENGLISH("English", "en")
}

enum class LyricsLanguage(val apiName: String, val labelHe: String, val labelEn: String) {
    HEBREW("Hebrew", "עברית", "Hebrew"),
    ENGLISH("English", "אנגלית", "English"),
    SPANISH("Spanish", "ספרדית", "Spanish"),
    FRENCH("French", "צרפתית", "French"),
    RUSSIAN("Russian", "רוסית", "Russian"),
    ARABIC("Arabic", "ערבית", "Arabic");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class DownloadSpeedMode(val parallelChunks: Int, val concurrentSongs: Int, val labelHe: String, val labelEn: String) {
    BALANCED(4, 1, "מאוזן (4 חיבורים)", "Balanced (4x)"),
    TURBO(6, 2, "טורבו (6 חיבורים)", "Turbo (6x)"),
    MAX(8, 2, "מקסימום (8 חיבורים)", "Max (8x)");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class AudioBufferProfile(val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int, val labelHe: String, val labelEn: String) {
    STANDARD(30_000, 90_000, 600, 1_800, "רגיל (30s)", "Standard (30s)"),
    HIGH_STABILITY(60_000, 180_000, 750, 2_200, "יציב לרשת חלשה (60s)", "Weak-Network Shield (60s)");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class SettingsSection {
    ROOT,
    UI_LANGUAGE,
    STREAM_QUALITY,
    PLAYBACK_AUDIO,
    EQUALIZER,
    PRIVACY_TASTE,
    STORAGE_OFFLINE,
    AI_NOTIFICATIONS,
    JAM_SOCIAL
}

enum class HomeFilter(val labelHe: String, val labelEn: String) {
    ALL("הכל", "All"),
    MUSIC("מוזיקה", "Music"),
    PODCASTS("פודקאסטים", "Podcasts"),
    AUDIOBOOKS("ספרי שמע", "Audiobooks");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class SearchMode(val labelHe: String, val labelEn: String) {
    SONGS("שירים", "Songs"),
    PLAYLISTS("פלייליסטים", "Playlists"),
    PODCASTS("פודקאסטים", "Podcasts"),
    AUDIOBOOKS("ספרי שמע", "Audiobooks");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class LibraryFilter(val labelHe: String, val labelEn: String) {
    ALL("הכל", "All"),
    PLAYLISTS("פלייליסטים", "Playlists"),
    FOLDERS("תיקיות", "Folders"),
    OFFLINE("הורדות אופליין", "Downloaded"),
    ARTISTS("אמנים", "Artists"),
    ALBUMS("אלבומים", "Albums"),
    PODCASTS("פודקאסטים", "Podcasts");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class LibrarySort(val labelHe: String, val labelEn: String) {
    RECENT("אחרונים", "Recents"),
    DATE_ADDED("תאריך הוספה", "Recently Added"),
    ALPHA("א'-ב'", "Alphabetical"),
    CREATOR("יוצר", "Creator"),
    CUSTOM("סדר מותאם", "Custom");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
}

enum class StreamSource(val labelHe: String, val labelEn: String, val subHe: String, val subEn: String) {
    YOUTUBE("YouTube Music (ברירת מחדל)", "YouTube Music (Default)", "שירים, פלייליסטים, פודקאסטים + קליפים רשמיים (עד 256kbps)", "Songs, playlists, podcasts + official videos (up to 256kbps)"),
    OPENSUBSONIC("שרת פרטי (OpenSubsonic / Navidrome)", "Private Server (OpenSubsonic / Navidrome)", "הזרמת Lossless FLAC ישירה מהשרת שלך", "True Lossless FLAC streaming from your server"),
    LOCAL("קובצי המכשיר (FLAC / MP3)", "Local Device Files (FLAC / MP3)", "ניגון ישיר באיכות מקורית ללא אינטרנט", "Direct native-quality playback from local storage");

    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
    fun sub(isEn: Boolean) = if (isEn) subEn else subHe

    fun supportedQualities(): List<AudioQuality> = when (this) {
        YOUTUBE -> listOf(AudioQuality.LOW, AudioQuality.NORMAL, AudioQuality.HIGH, AudioQuality.VERY_HIGH)
        OPENSUBSONIC -> AudioQuality.entries
        LOCAL -> emptyList()
    }

    fun clampQuality(q: AudioQuality): AudioQuality {
        val list = supportedQualities()
        return if (list.isEmpty() || q in list) q else list.last()
    }
}

enum class YouTubeEngineMode(val labelHe: String, val labelEn: String, val subHe: String, val subEn: String) {
    AUTO("אוטומטי (ExoPlayer + גיבוי Web)", "Auto (ExoPlayer + Web Fallback)", "מנסה אודיו ישיר עם אקולייזר ומטמון דיסק ועובר לגיבוי אוטומטי בעת חסימה", "Direct audio with EQ & disk cache, auto web fallback if blocked"),
    WEB_DIRECT("נגן YouTube מובנה (מהיר וללא 403)", "Built-in YouTube Engine (Fast, No 403)", "ניגון מיידי ללא חסימות סלולר + מעבר לקליפ רשמי", "Instant playback on cellular + official video switch"),
    EXOPLAYER_ONLY("ExoPlayer בלבד", "ExoPlayer Only", "הזרמה ישירה בלבד (אקולייזר 10 ערוצים, מונו ואיזון)", "Direct stream only (10-band EQ, Mono & Balance)");
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
    fun sub(isEn: Boolean) = if (isEn) subEn else subHe
}

enum class AudioQuality(val labelHe: String, val labelEn: String, val shortHe: String, val shortEn: String, val kbps: Int) {
    LOW("נמוכה (24kbps)", "Low (24kbps)", "נמוכה", "Low", 24),
    NORMAL("רגילה (96kbps)", "Normal (96kbps)", "רגילה", "Normal", 96),
    HIGH("גבוהה (160kbps)", "High (160kbps)", "גבוהה", "High", 160),
    VERY_HIGH("גבוהה מאוד (320kbps)", "Very High (320kbps)", "גבוהה מאוד", "Very High", 320),
    LOSSLESS("Lossless (FLAC 24-bit/44.1kHz)", "Lossless (FLAC 24-bit/44.1kHz)", "Lossless", "Lossless", 1411);

    fun shortLabel(isEn: Boolean, source: StreamSource = StreamSource.OPENSUBSONIC): String {
        if (source == StreamSource.YOUTUBE) {
            return when (this) {
                LOW -> if (isEn) "Low (48k)" else "נמוכה (48k)"
                NORMAL -> if (isEn) "Normal (96k)" else "רגילה (96k)"
                HIGH -> if (isEn) "High (128k)" else "גבוהה (128k)"
                VERY_HIGH, LOSSLESS -> if (isEn) "Max (~256k)" else "מקסימלית (~256k)"
            }
        }
        return if (isEn) shortEn else shortHe
    }
}

enum class NormalizationLevel(val labelHe: String, val labelEn: String, val gainMb: Int) {
    QUIET("שקט", "Quiet", 0),
    NORMAL("רגיל", "Normal", 250),
    LOUD("חזק", "Loud", 550);
    fun label(isEn: Boolean) = if (isEn) labelEn else labelHe
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
    val headers: Map<String, String> = emptyMap(),
    val isAudioOnly: Boolean = true,
    val capturedAt: Long = System.currentTimeMillis()
)

data class ResolvedStreams(
    val audioUrl: String,
    val videoUrl: String? = null,
    val videoOnlyUrl: String? = null,
    val userAgent: String = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
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
    val text: String,
    val translatedText: String? = null
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
    val aiSeedPrompt: String = "",
    val aiStyleTag: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isAudiobookPlaylist: Boolean
        get() = id.startsWith("book_") ||
            id.contains("AUDIOBOOKS", true) ||
            aiStyleTag.equals("AUDIOBOOK", true) ||
            creator.contains("Audiobook", true) ||
            (tracks.isNotEmpty() && tracks.all { it.isAudiobook })

    val isPodcastPlaylist: Boolean
        get() = !isAudiobookPlaylist && (
            id.startsWith("pod_") ||
                id.contains("PODCASTS", true) ||
                aiStyleTag.equals("PODCAST", true) ||
                creator.contains("Podcast", true) ||
                (tracks.isNotEmpty() && tracks.all { it.isPodcast })
            )

    fun countLabel(str: AppStrings): String = when {
        isAudiobookPlaylist -> "${tracks.size} ${str.books}"
        isPodcastPlaylist -> "${tracks.size} ${str.episodes}"
        else -> "${tracks.size} ${str.songs}"
    }
}

data class HomeFeedSection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val playlists: List<Playlist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val remotePlaylists: List<RemotePlaylist> = emptyList()
)

data class PlaylistFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

data class DeletedPlaylistRecord(
    val playlist: Playlist,
    val deletedAt: Long = System.currentTimeMillis()
)

data class FriendProfile(
    val name: String,
    val currentActivity: String = "",
    val isOnline: Boolean = true
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val friendThread: String = "כללי",
    val sender: String,
    val text: String,
    val sharedTrack: Track? = null,
    val sharedPlaylistName: String? = null,
    val sharedJamCode: String? = null,
    val trackTimestampSec: Int? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ListeningStats(
    val totalListeningMs: Long = 0L,
    val trackPlayCounts: Map<String, Int> = emptyMap(),
    val artistPlayCounts: Map<String, Int> = emptyMap()
)

data class ArtistPageState(
    val artistName: String,
    val artworkUrl: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false
)

data class AiPlaylistConfig(
    val prompt: String,
    val targetCount: Int = 25,
    val languagePref: String = "משולב",
    val energyPref: String = "מאוזן",
    val eraPref: String = "הכל"
)

data class AiDjConfig(
    val styleName: String = "",
    val customPrompt: String = "",
    val speakAtStart: Boolean = true,
    val speakBetweenTracks: Boolean = true
)

data class FriendTasteProfile(
    val name: String,
    val artists: List<String>,
    val trackIds: List<String> = emptyList()
)

data class SharedPlaylistToken(
    val name: String,
    val creator: String,
    val trackIds: List<String>
)

object BlendTasteCodec {
    fun encodeTaste(name: String, artists: List<String>, trackIds: List<String>): String {
        val obj = JSONObject().apply {
            put("n", name.trim().ifBlank { "חבר" }.take(20))
            put("a", JSONArray().apply { artists.distinct().take(8).forEach { put(it.take(32)) } })
            put("t", JSONArray().apply { trackIds.distinct().take(6).forEach { put(it) } })
        }
        val b64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "sonify:blend:$b64"
    }

    fun decodeTaste(raw: String): FriendTasteProfile? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("sonify:blend:", ignoreCase = true)) return null
        return runCatching {
            val b64 = trimmed.substringAfter("sonify:blend:").trim()
            val json = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE), Charsets.UTF_8))
            val name = json.optString("n", "חבר")
            val artistsArr = json.optJSONArray("a") ?: JSONArray()
            val tracksArr = json.optJSONArray("t") ?: JSONArray()
            val artists = List(artistsArr.length()) { artistsArr.optString(it) }.filter { it.isNotBlank() }
            val tracks = List(tracksArr.length()) { tracksArr.optString(it) }.filter { it.length == 11 }
            FriendTasteProfile(name, artists, tracks)
        }.getOrNull()
    }

    fun encodeSharedPlaylist(pl: Playlist): String {
        val obj = JSONObject().apply {
            put("n", pl.name.take(32))
            put("c", pl.creator.take(20))
            put("t", JSONArray().apply {
                pl.tracks.filter { it.source == StreamSource.YOUTUBE && it.id.length == 11 }.take(25).forEach { put(it.id) }
            })
        }
        val b64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "sonify:plshare:$b64"
    }

    fun decodeSharedPlaylist(raw: String): SharedPlaylistToken? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("sonify:plshare:", ignoreCase = true)) return null
        return runCatching {
            val b64 = trimmed.substringAfter("sonify:plshare:").trim()
            val json = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE), Charsets.UTF_8))
            val name = json.optString("n", "פלייליסט משותף")
            val creator = json.optString("c", "חבר")
            val arr = json.optJSONArray("t") ?: JSONArray()
            val ids = List(arr.length()) { arr.optString(it) }.filter { it.length == 11 }
            if (ids.isEmpty()) null else SharedPlaylistToken(name, creator, ids)
        }.getOrNull()
    }
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.HEBREW,
    val showCreateNavButton: Boolean = true,
    val showUnplayableSongs: Boolean = true,
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
    val djVoiceEnabled: Boolean = true,
    val djIntroVoice: Boolean = true,
    val djBetweenTracksVoice: Boolean = true,
    val normalizeVolume: Boolean = true,
    val normalizationLevel: NormalizationLevel = NormalizationLevel.NORMAL,
    val gaplessPlayback: Boolean = true,
    val automixEnabled: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val monoAudio: Boolean = false,
    val audioBalance: Float = 0f,
    val autoplayEnabled: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val audioOnlyPodcasts: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPresetIndex: Int = 0,
    val eqBands: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    val canvasEnabled: Boolean = true,
    val autoVideoOnWifi: Boolean = false,
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
    val carModeOptimized: Boolean = false,
    val infiniteAiHome: Boolean = true,
    val showYoutubePlaylistsHome: Boolean = true,
    val aiUniqueCovers: Boolean = true,
    val downloadSpeedMode: DownloadSpeedMode = DownloadSpeedMode.TURBO,
    val audioBufferProfile: AudioBufferProfile = AudioBufferProfile.HIGH_STABILITY,
    val keepWakeLockAudio: Boolean = true,
    val jamLiveSyncEnabled: Boolean = true,
    val jamSyncServerUrl: String = "https://ntfy.sh",
    val jamUserDisplayName: String = "",
    val jamSyncToleranceSec: Int = 3,
    val lyricsTargetLanguage: LyricsLanguage = LyricsLanguage.HEBREW,
    val autoTranslateLyrics: Boolean = false
)

data class AudioOutputItem(
    val id: Int,
    val name: String,
    val typeLabelHe: String,
    val typeLabelEn: String,
    val isLosslessCapable: Boolean,
    val isActive: Boolean
) {
    fun typeLabel(isEn: Boolean) = if (isEn) typeLabelEn else typeLabelHe
}

data class JamState(
    val active: Boolean = false,
    val code: String = "",
    val joinUrl: String = "",
    val isHost: Boolean = true,
    val guestsCanControlQueue: Boolean = true,
    val guestsCanControlVolume: Boolean = false,
    val participants: List<String> = emptyList(),
    val liveConnected: Boolean = false,
    val syncStatus: String = ""
)

data class BrowseCategory(
    val title: String,
    val titleEn: String,
    val query: String,
    val colorHex: Long,
    val group: String,
    val groupEn: String,
    val searchMode: SearchMode = SearchMode.SONGS
) {
    fun displayTitle(isEn: Boolean) = if (isEn) titleEn else title
    fun displayGroup(isEn: Boolean) = if (isEn) groupEn else group
}
