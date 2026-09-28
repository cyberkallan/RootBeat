package com.unshoo.pixelmusic.data.visualizer

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioVisualizerEngine @Inject constructor() {
    private val _bars = MutableStateFlow(FloatArray(BAR_COUNT))
    val bars: StateFlow<FloatArray> = _bars.asStateFlow()

    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int = 0
    private var lastSessionId: Int = 0

    fun attach(audioSessionId: Int, force: Boolean = false) {
        if (audioSessionId == 0) return
        lastSessionId = audioSessionId
        if (!force && audioSessionId == attachedSessionId && visualizer != null) return
        releaseVisualizer()
        try {
            val created = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            created.captureSize = range[1]
            created.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int,
                    ) {
                        val next = VisualizerLevels.fftToBars(fft, BAR_COUNT)
                        _bars.value = VisualizerLevels.smooth(_bars.value, next)
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true,
            )
            created.enabled = true
            visualizer = created
            attachedSessionId = audioSessionId
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "Audio visualizer could not attach to session $audioSessionId")
            releaseVisualizer()
        }
    }

    fun retry() {
        if (lastSessionId != 0) attach(lastSessionId, force = true)
    }

    fun release() {
        releaseVisualizer()
        lastSessionId = 0
    }

    private fun releaseVisualizer() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        attachedSessionId = 0
    }

    private companion object {
        const val TAG = "RootBeatVisualizer"
        const val BAR_COUNT = 32
    }
}
