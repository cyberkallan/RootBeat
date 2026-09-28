package com.unshoo.pixelmusic.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.unshoo.pixelmusic.data.visualizer.AudioVisualizerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class VisualizerViewModel @Inject constructor(
    private val engine: AudioVisualizerEngine,
) : ViewModel() {
    val bars = engine.bars

    fun retry() = engine.retry()
}
