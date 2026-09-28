package com.unshoo.pixelmusic.data.jam

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callbacks from the listen-together session into the app player.
 * Player methods must be invoked on the main thread.
 */
@Singleton
class JamPlaybackBridge @Inject constructor() {
    @Volatile var snapshot: (() -> JamPlaybackSnapshot?)? = null
    @Volatile var play: (() -> Unit)? = null
    @Volatile var pause: (() -> Unit)? = null
    @Volatile var seek: ((Long) -> Unit)? = null
    @Volatile var next: (() -> Unit)? = null
    @Volatile var previous: (() -> Unit)? = null
    @Volatile var playRemote: ((JamTrack, Long) -> Unit)? = null
    @Volatile var enqueue: ((String) -> Unit)? = null
    @Volatile var search: (suspend (String) -> List<JamTrackRef>)? = null
    @Volatile var contentUri: (suspend (String) -> String?)? = null

    fun bind(
        snapshot: () -> JamPlaybackSnapshot?,
        play: () -> Unit,
        pause: () -> Unit,
        seek: (Long) -> Unit,
        next: () -> Unit,
        previous: () -> Unit,
        playRemote: (JamTrack, Long) -> Unit,
        enqueue: (String) -> Unit,
        search: suspend (String) -> List<JamTrackRef>,
        contentUri: suspend (String) -> String?,
    ) {
        this.snapshot = snapshot
        this.play = play
        this.pause = pause
        this.seek = seek
        this.next = next
        this.previous = previous
        this.playRemote = playRemote
        this.enqueue = enqueue
        this.search = search
        this.contentUri = contentUri
    }

    fun unbind() {
        snapshot = null
        play = null
        pause = null
        seek = null
        next = null
        previous = null
        playRemote = null
        enqueue = null
        search = null
        contentUri = null
    }
}
