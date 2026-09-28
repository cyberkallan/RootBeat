package com.unshoo.pixelmusic.data.remote.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YouTubeCatalogParserTest {
    @Test
    fun parseReadsSongTitleArtistAndDuration() {
        val tracks = YouTubeCatalogParser.parse(SAMPLE)

        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("xukbqwRuN5w", track.videoId)
        assertEquals("Choosin' Texas", track.title)
        assertEquals("Ella Langley", track.artist)
        assertEquals("YouTube Music", track.album)
        assertEquals(195_000L, track.durationMs)
        assertTrue(track.thumbnailUrl!!.contains("cover.jpg"))
    }

    @Test
    fun longMixesAreDroppedFromTheCatalog() {
        val mix = YouTubeTrack(
            videoId = "abcdefghijk",
            title = "Top Hits 2026",
            artist = "Various",
            album = "YouTube Music",
            durationMs = 22 * 60 * 1000L,
            thumbnailUrl = null
        )
        val song = mix.copy(durationMs = 180_000L, title = "Night Drive")

        assertFalse(YouTubeCatalogParser.isUsefulCatalogTrack(mix))
        assertTrue(YouTubeCatalogParser.isSearchTrack(mix))
        assertTrue(YouTubeCatalogParser.isUsefulCatalogTrack(song))
    }

    private companion object {
        private val SAMPLE = """
            {
              "contents": {
                "musicResponsiveListItemRenderer": {
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Choosin' Texas" }] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Ella Langley • 3:15 • 308M plays" }] }
                      }
                    }
                  ],
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://example.com/small.jpg", "width": 60 },
                          { "url": "https://example.com/cover.jpg", "width": 226 }
                        ]
                      }
                    }
                  },
                  "overlay": {
                    "musicItemThumbnailOverlayRenderer": {
                      "content": {
                        "musicPlayButtonRenderer": {
                          "playNavigationEndpoint": {
                            "watchEndpoint": { "videoId": "xukbqwRuN5w" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
