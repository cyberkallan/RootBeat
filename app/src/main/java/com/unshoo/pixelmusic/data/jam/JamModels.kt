package com.unshoo.pixelmusic.data.jam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JamTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val streamUrl: String = "",
)

@Serializable
data class JamTrackRef(
    val id: String,
    val title: String,
    val artist: String,
)

@Serializable
data class JamMember(
    val id: String,
    val name: String,
    val isHost: Boolean = false,
)

@Serializable
data class JamMessage(
    val type: String,
    val code: String = "",
    val revision: Long = 0,
    val sentAtEpochMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val track: JamTrack? = null,
    val members: List<JamMember> = emptyList(),
    val upNext: List<JamTrackRef> = emptyList(),
    val memberId: String = "",
    val name: String = "",
    val action: String = "",
    val query: String = "",
    val songId: String = "",
    val results: List<JamTrackRef> = emptyList(),
    val error: String = "",
)

data class JamPlaybackSnapshot(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val contentUri: String,
    val upNext: List<JamTrackRef>,
)

const val DEFAULT_PORT = 28765

enum class JamRole { Idle, Host, Guest }

data class NearbyJamHost(
    val name: String,
    val host: String,
    val port: Int,
)

data class JamUiState(
    val role: JamRole = JamRole.Idle,
    val code: String = "",
    val hostAddress: String = "",
    val port: Int = DEFAULT_PORT,
    val members: List<JamMember> = emptyList(),
    val trackTitle: String = "",
    val trackArtist: String = "",
    val isPlaying: Boolean = false,
    val upNext: List<JamTrackRef> = emptyList(),
    val searchResults: List<JamTrackRef> = emptyList(),
    val nearby: List<NearbyJamHost> = emptyList(),
    val status: String = "",
    val error: String = "",
)

object JamCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: JamMessage): String = json.encodeToString(message)

    fun decode(text: String): JamMessage = json.decodeFromString(text)
}

object JamAudio {
    fun parseRange(header: String?, size: Long): Triple<Long, Long, Boolean> {
        if (size <= 0) return Triple(0L, 0L, false)
        if (header.isNullOrBlank() || !header.startsWith("bytes=")) {
            return Triple(0L, size - 1, false)
        }
        val spec = header.removePrefix("bytes=").substringBefore(",")
        val parts = spec.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull() ?: (size - 1)
        val safeStart = start.coerceIn(0L, size - 1)
        val safeEnd = end.coerceIn(safeStart, size - 1)
        return Triple(safeStart, safeEnd, true)
    }
}
