package com.theveloper.pixelplay.data.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal iterative radix-2 Cooley-Tukey FFT.
 *
 * There's no audio-DSP library in this project (no librosa equivalent on Android), so
 * [MoodAnalyzer] needs its own tiny FFT to get a spectral centroid ("brightness") and a
 * chroma vector (for major/minor tonality) out of decoded PCM. Deliberately small and
 * dependency-free rather than fast/general: callers always pass power-of-two sizes.
 */
internal object SimpleFFT {

    /**
     * In-place FFT. [real] and [imag] must have the same power-of-two length.
     * On return, `real[k]`/`imag[k]` hold the complex spectrum bin k.
     */
    fun transform(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, was $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Iterative Cooley-Tukey butterflies.
        var size = 2
        while (size <= n) {
            val half = size / 2
            val angleStep = -2.0 * PI / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val angle = angleStep * k
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()
                    val evenIdx = start + k
                    val oddIdx = start + k + half
                    val oddR = real[oddIdx] * wr - imag[oddIdx] * wi
                    val oddI = real[oddIdx] * wi + imag[oddIdx] * wr
                    real[oddIdx] = real[evenIdx] - oddR
                    imag[oddIdx] = imag[evenIdx] - oddI
                    real[evenIdx] += oddR
                    imag[evenIdx] += oddI
                }
                start += size
            }
            size = size shl 1
        }
    }

    /** Magnitude spectrum (length n/2 + 1, DC .. Nyquist) of a real-valued, Hann-windowed frame. */
    fun magnitudeSpectrum(frame: FloatArray): FloatArray {
        val n = frame.size
        val real = frame.copyOf()
        val imag = FloatArray(n)
        transform(real, imag)
        val half = n / 2
        val mags = FloatArray(half + 1)
        for (i in 0..half) {
            mags[i] = kotlin.math.hypot(real[i].toDouble(), imag[i].toDouble()).toFloat()
        }
        return mags
    }
}

/** Applies a Hann window in place. */
internal fun applyHannWindow(frame: FloatArray) {
    val n = frame.size
    if (n <= 1) return
    for (i in frame.indices) {
        val w = (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))).toFloat()
        frame[i] = frame[i] * w
    }
}
