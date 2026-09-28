package com.unshoo.pixelmusic.data.visualizer

import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns an Android [android.media.audiofx.Visualizer] FFT payload into bar heights
 * from 0 to 1. The payload layout is DC at index 0, then real/imaginary pairs.
 */
object VisualizerLevels {
    fun fftToBars(fft: ByteArray, barCount: Int): FloatArray {
        if (barCount <= 0) return FloatArray(0)
        val bins = (fft.size / 2) - 1
        if (bins <= 0) return FloatArray(barCount)
        val magnitudes = FloatArray(bins)
        for (bin in 0 until bins) {
            val index = (bin + 1) * 2
            if (index + 1 >= fft.size) break
            val real = fft[index].toInt().toFloat()
            val imaginary = fft[index + 1].toInt().toFloat()
            magnitudes[bin] = (hypot(real, imaginary) / 128f).coerceIn(0f, 1f)
        }
        val bars = FloatArray(barCount)
        for (bar in 0 until barCount) {
            val start = (bar * bins) / barCount
            val end = max(((bar + 1) * bins) / barCount, start + 1).coerceAtMost(bins)
            var peak = 0f
            for (index in start until end) {
                if (magnitudes[index] > peak) peak = magnitudes[index]
            }
            bars[bar] = peak
        }
        return bars
    }

    fun smooth(previous: FloatArray, next: FloatArray): FloatArray {
        return FloatArray(next.size) { index ->
            val prior = previous.getOrElse(index) { 0f }
            val incoming = next[index]
            if (incoming >= prior) incoming else prior * 0.72f
        }
    }
}
