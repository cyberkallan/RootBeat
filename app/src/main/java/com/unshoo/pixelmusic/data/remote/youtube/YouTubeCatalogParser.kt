package com.unshoo.pixelmusic.data.remote.youtube

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls playable songs out of a YouTube Music browse or search response.
 * The response shape changes often, so this walks the tree instead of binding every renderer.
 */
object YouTubeCatalogParser {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val durationPattern = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})""")
    private const val MAX_CATALOG_DURATION_MS = 12 * 60 * 1000L
    private const val MIN_CATALOG_DURATION_MS = 45_000L

    fun parse(json: String): List<YouTubeTrack> {
        if (json.isBlank()) return emptyList()
        val tracks = LinkedHashMap<String, YouTubeTrack>()
        walk(JSONObject(json), tracks)
        return tracks.values.toList()
    }

    fun isUsefulCatalogTrack(track: YouTubeTrack): Boolean {
        if (!videoIdPattern.matches(track.videoId) || track.title.isBlank()) return false
        if (track.durationMs <= 0L) return true
        return track.durationMs in MIN_CATALOG_DURATION_MS..MAX_CATALOG_DURATION_MS
    }

    private fun walk(node: Any?, tracks: LinkedHashMap<String, YouTubeTrack>) {
        when (node) {
            is JSONObject -> {
                renderer(node, "musicResponsiveListItemRenderer")?.let { addTrack(it, tracks) }
                renderer(node, "musicTwoRowItemRenderer")?.let { addTrack(it, tracks) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walk(node.opt(keys.next()), tracks)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walk(node.opt(index), tracks)
                }
            }
        }
    }

    private fun renderer(node: JSONObject, name: String): JSONObject? {
        val value = node.opt(name)
        return value as? JSONObject
    }

    private fun addTrack(item: JSONObject, tracks: LinkedHashMap<String, YouTubeTrack>) {
        val videoId = findVideoId(item) ?: return
        if (!videoIdPattern.matches(videoId) || tracks.containsKey(videoId)) return
        val columns = flexColumnTexts(item)
        val title = columns.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: textOf(item.optJSONObject("title"))
        if (title.isBlank()) return
        val subtitle = columns.getOrNull(1).orEmpty().ifBlank { textOf(item.optJSONObject("subtitle")) }
        val (artist, album) = splitSubtitle(subtitle)
        tracks[videoId] = YouTubeTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = findDurationMs(item),
            thumbnailUrl = largestThumbnail(item)
        )
    }

    private fun flexColumnTexts(item: JSONObject): List<String> {
        val columns = item.optJSONArray("flexColumns") ?: return emptyList()
        val texts = ArrayList<String>(columns.length())
        for (index in 0 until columns.length()) {
            val column = columns.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?: continue
            val text = textOf(column)
            if (text.isNotBlank()) texts.add(text)
        }
        return texts
    }

    private fun textOf(node: JSONObject?): String {
        if (node == null) return ""
        val direct = node.optJSONObject("text")
        val runs = direct?.optJSONArray("runs") ?: node.optJSONArray("runs")
        if (runs == null) return ""
        val builder = StringBuilder()
        for (index in 0 until runs.length()) {
            val piece = runs.optJSONObject(index)?.optString("text").orEmpty()
            builder.append(piece)
        }
        return builder.toString().trim()
    }

    private fun splitSubtitle(subtitle: String): Pair<String, String> {
        if (subtitle.isBlank()) return "YouTube Music" to "YouTube Music"
        val parts = subtitle.split(" • ", " · ")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("play", ignoreCase = true) }
        val artist = parts.firstOrNull()?.takeUnless { looksLikeDuration(it) } ?: "YouTube Music"
        val album = parts.drop(1).firstOrNull()?.takeUnless { looksLikeDuration(it) } ?: "YouTube Music"
        return artist to album
    }

    private fun looksLikeDuration(value: String): Boolean = durationPattern.containsMatchIn(value)

    private fun findVideoId(node: JSONObject): String? {
        val watch = node.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
        if (videoIdPattern.matches(watch)) return watch
        val playlistItem = node.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoIdPattern.matches(playlistItem)) return playlistItem
        val keys = node.keys()
        while (keys.hasNext()) {
            val child = node.opt(keys.next())
            val found = when (child) {
                is JSONObject -> findVideoId(child)
                is JSONArray -> {
                    var match: String? = null
                    for (index in 0 until child.length()) {
                        val item = child.opt(index)
                        if (item is JSONObject) {
                            match = findVideoId(item)
                            if (match != null) break
                        }
                    }
                    match
                }
                else -> null
            }
            if (found != null) return found
        }
        return null
    }

    private fun findDurationMs(node: Any?): Long {
        when (node) {
            null -> return 0L
            is JSONObject -> {
                val text = node.optString("text")
                if (text.isNotBlank()) {
                    durationMillis(text)?.let { return it }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findDurationMs(node.opt(keys.next()))
                    if (found > 0L) return found
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val found = findDurationMs(node.opt(index))
                    if (found > 0L) return found
                }
            }
            else -> return 0L
        }
        return 0L
    }

    private fun durationMillis(text: String): Long? {
        val match = durationPattern.find(text) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000L
    }

    private fun largestThumbnail(node: JSONObject): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val thumbnails = value.optJSONArray("thumbnails")
                    if (thumbnails != null) {
                        for (index in 0 until thumbnails.length()) {
                            val thumb = thumbnails.optJSONObject(index) ?: continue
                            val url = thumb.optString("url")
                            if (url.isBlank()) continue
                            val width = thumb.optInt("width", 0)
                            if (width >= bestWidth) {
                                bestWidth = width
                                bestUrl = url
                            }
                        }
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index))
                }
                else -> Unit
            }
        }
        walk(node)
        return bestUrl
    }
}
