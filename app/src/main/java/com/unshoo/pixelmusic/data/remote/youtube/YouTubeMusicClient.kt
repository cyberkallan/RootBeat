package com.unshoo.pixelmusic.data.remote.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public YouTube Music catalog and audio URL lookup.
 * The API key is the public web client key already used by music.youtube.com.
 */
@Singleton
class YouTubeMusicClient @Inject constructor() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun loadCatalog(): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val tracks = LinkedHashMap<String, YouTubeTrack>()
        fun accept(json: String) {
            YouTubeCatalogParser.parse(json)
                .filter(YouTubeCatalogParser::isUsefulCatalogTrack)
                .forEach { track ->
                    if (tracks.size < MAX_TRACKS) {
                        tracks.putIfAbsent(track.videoId, track)
                    }
                }
        }

        runCatching { accept(postBrowse("FEmusic_home")) }
            .onFailure { Timber.tag(TAG).w(it, "YouTube Music home request failed") }
        for (query in SEARCH_QUERIES) {
            if (tracks.size >= MAX_TRACKS) break
            runCatching { accept(postSearch(query)) }
                .onFailure { Timber.tag(TAG).w(it, "YouTube Music search failed for %s", query) }
        }
        tracks.values.toList()
    }

    suspend fun resolveAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        for (client in PLAYER_CLIENTS) {
            val url = runCatching { audioUrlFromPlayer(videoId, client) }.getOrNull()
            if (!url.isNullOrBlank()) return@withContext url
        }
        null
    }

    private fun postBrowse(browseId: String): String {
        val body = JSONObject()
            .put("context", webRemixContext())
            .put("browseId", browseId)
        return post("https://music.youtube.com/youtubei/v1/browse?$API_QUERY", body)
    }

    private fun postSearch(query: String): String {
        val body = JSONObject()
            .put("context", webRemixContext())
            .put("query", query)
            .put("params", SONGS_FILTER)
        return post("https://music.youtube.com/youtubei/v1/search?$API_QUERY", body)
    }

    private fun audioUrlFromPlayer(videoId: String, client: PlayerClient): String? {
        val body = JSONObject()
            .put("context", JSONObject().put("client", client.payload))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val json = post(client.endpoint, body, client.userAgent)
        val root = JSONObject(json)
        val status = root.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK") return null
        val streaming = root.optJSONObject("streamingData") ?: return null
        return bestAudioUrl(streaming.optJSONArray("adaptiveFormats"))
            ?: bestAudioUrl(streaming.optJSONArray("formats"))
    }

    private fun bestAudioUrl(formats: org.json.JSONArray?): String? {
        if (formats == null) return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("audio")) continue
            val url = format.optString("url")
            if (url.isBlank()) continue
            val bitrate = format.optInt("bitrate", 0)
            if (bitrate >= bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun post(url: String, body: JSONObject, userAgent: String = WEB_USER_AGENT): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("YouTube Music HTTP ${response.code}")
            }
            return payload
        }
    }

    private fun webRemixContext(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250310.01.00")
            .put("hl", "en")
            .put("gl", "US")
    )

    private data class PlayerClient(
        val endpoint: String,
        val userAgent: String,
        val payload: JSONObject
    )

    private companion object {
        private const val TAG = "YouTubeMusicClient"
        private const val MAX_TRACKS = 120
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        private const val API_QUERY = "key=$API_KEY&prettyPrint=false"
        private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val SEARCH_QUERIES = listOf("top songs", "new music", "pop hits", "hip hop")
        private val PLAYER_CLIENTS = listOf(
            PlayerClient(
                endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
                payload = JSONObject()
                    .put("clientName", "ANDROID")
                    .put("clientVersion", "20.10.38")
                    .put("androidSdkVersion", 34)
                    .put("hl", "en")
                    .put("gl", "US")
                    .put("osName", "Android")
                    .put("osVersion", "14")
            ),
            PlayerClient(
                endpoint = "https://music.youtube.com/youtubei/v1/player?$API_QUERY",
                userAgent = WEB_USER_AGENT,
                payload = JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", "1.20250310.01.00")
                    .put("hl", "en")
                    .put("gl", "US")
            )
        )
    }
}
