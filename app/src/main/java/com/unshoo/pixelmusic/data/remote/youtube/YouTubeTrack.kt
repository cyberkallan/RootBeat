package com.unshoo.pixelmusic.data.remote.youtube

data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?
)
