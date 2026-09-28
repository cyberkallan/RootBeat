package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.unshoo.pixelmusic.data.jam.JamSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    private val session: JamSessionManager,
) : ViewModel() {
    val ui = session.ui

    fun startHosting() = session.startHosting()

    fun join(code: String, host: String, port: Int = com.unshoo.pixelmusic.data.jam.DEFAULT_PORT) {
        session.join(code, host, port)
    }

    fun findNearby() = session.findNearby()

    fun search(query: String) = session.search(query)

    fun addSong(songId: String) = session.addSong(songId)

    fun togglePlayback() = session.togglePlayback()

    fun next() = session.next()

    fun previous() = session.previous()

    fun leave() = session.leave()
}
