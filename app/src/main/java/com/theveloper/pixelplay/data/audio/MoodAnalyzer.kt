package com.theveloper.pixelplay.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Raw (un-normalized) audio features extracted from a short sample of one track. */
data class RawAudioFeatures(
    val tempoBpm: Float,
    val rmsLoudness: Float,
    val onsetDensity: Float,
    val brightness: Float,
    val tonality: Float
)

/**
 * Estimates mood-relevant audio features on-device — a much smaller, dependency-free cousin of
 * the librosa-based analysis the original desktop Music Square tool does. There's no
 * tempo/key-estimation library available on Android here, so this decodes a short window of
 * audio via [MediaExtractor]/[MediaCodec] and runs a small hand-rolled DSP pipeline:
 *
 *  - **Tempo** — autocorrelation of a frame-energy onset envelope, searched over the 50-200 BPM
 *    range.
 *  - **Loudness** — overall RMS of the sampled window.
 *  - **Onset density** — rate of energy-novelty peaks per second (rhythmic busyness).
 *  - **Brightness** — average spectral centroid (via a small in-house FFT), normalized by
 *    Nyquist.
 *  - **Tonality** — chroma vector (12 pitch classes, folded from FFT magnitude bins) correlated
 *    against Krumhansl-Schmuckler major/minor key profiles across all 12 rotations; the
 *    best-matching major correlation minus the best-matching minor correlation gives a
 *    -1 (minor) .. +1 (major) score.
 *
 * This is a heuristic, not a trained classifier — like the desktop tool, it's aiming to
 * separate "slow and moody" from "fast and upbeat" well enough to spread a library across a
 * square, not to be musicologically precise.
 *
 * @param snippetSeconds how much audio to sample per track. [FULL_TRACK] (0) decodes the whole
 * song instead of a short window — slower and more battery-hungry, but avoids missing a track
 * whose mood shifts a lot over its runtime.
 * @param startOffsetFraction how far into the track (0f..1f) a partial snippet should start
 * sampling from, so the analyzed window can skip past a long intro instead of always starting
 * at 0s. Only meaningful when [snippetSeconds] isn't [FULL_TRACK] — full-track mode always
 * starts at 0 since it wants the whole song anyway.
 */
class MoodAnalyzer(
    private val context: Context,
    private val snippetSeconds: Int = DEFAULT_SNIPPET_SECONDS,
    startOffsetFraction: Float = DEFAULT_START_OFFSET_FRACTION
) {

    /** Target sample rate we downsample decoded audio to before running DSP — plenty for tempo/tonality, and fast. */
    private val analysisSampleRate = 11025

    /** How much audio to sample per track; null means "the whole track" (see [snippetSeconds]). */
    private val sampleDurationSeconds: Int? = if (snippetSeconds <= FULL_TRACK) null else snippetSeconds

    /** Skip the cold intro; start partway into the track, mirroring the desktop tool — how far in is caller-configurable via [startOffsetFraction] (see the Music Square settings' "Where in each song?" picker). Full-track mode starts at 0 since it wants the whole song anyway. */
    private val startFraction = if (sampleDurationSeconds != null) startOffsetFraction.coerceIn(0f, 0.9f) else 0f

    /** Wall-clock decode budget. Full-track mode needs materially longer since it may decode several minutes of audio. */
    private val maxDecodeMs = if (sampleDurationSeconds != null) 15_000L else 90_000L

    /** Safety cap on how much of a track full-track mode will decode, so a pathologically long file can't run away with memory/time. */
    private val fullTrackCapSeconds = 20 * 60

    private val frameSize = 2048
    private val hopSize = 1024

    /**
     * Decodes a sample of [contentUri] and estimates [RawAudioFeatures], or null if the file
     * couldn't be decoded (corrupt file, DRM, unsupported codec, etc.) — callers should fall
     * back to a metadata-only prior in that case.
     */
    fun analyze(contentUri: String, durationMs: Long): RawAudioFeatures? {
        return try {
            val pcm = decodeMonoPcm(Uri.parse(contentUri), durationMs) ?: return null
            if (pcm.size < frameSize * 4) return null
            computeFeatures(pcm)
        } catch (t: Throwable) {
            Timber.tag("MoodAnalyzer").w(t, "Failed to analyze $contentUri")
            null
        }
    }

    // region Decoding

    /** Decodes a window of [contentUri] to mono float PCM at [analysisSampleRate]. */
    private fun decodeMonoPcm(uri: Uri, totalDurationMs: Long): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)

            val startUs = if (totalDurationMs > 0) {
                (totalDurationMs * 1000L * startFraction).toLong()
            } else 0L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            var sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val effectiveSampleDurationSeconds = sampleDurationSeconds ?: run {
                // Full-track mode: target the track's own duration (capped), rather than a fixed window.
                val reportedSeconds = if (totalDurationMs > 0) (totalDurationMs / 1000.0).toInt() else fullTrackCapSeconds
                reportedSeconds.coerceIn(1, fullTrackCapSeconds)
            }
            val targetSampleCount = (effectiveSampleDurationSeconds * sourceSampleRate).coerceAtLeast(1)
            val monoOut = ArrayList<Float>(min(targetSampleCount, 30 * 48000))

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var decodedSamplesPerChannel = 0
            val decodeStartWall = System.currentTimeMillis()

            while (!sawOutputEos) {
                if (System.currentTimeMillis() - decodeStartWall > maxDecodeMs) break

                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outIndex)
                            if (outputBuffer != null) {
                                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                appendPcm16AsMonoFloat(outputBuffer, sourceChannels, monoOut)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                        decodedSamplesPerChannel = monoOut.size
                        if (decodedSamplesPerChannel >= targetSampleCount) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                    }
                    else -> Unit // try again
                }
            }

            if (monoOut.size < frameSize) return null

            return downsample(monoOut, sourceSampleRate, analysisSampleRate)
        } catch (t: Throwable) {
            Timber.tag("MoodAnalyzer").w(t, "Decode failed for $uri")
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** Reads 16-bit PCM samples from [buffer], downmixing [channelCount] channels to mono, appending to [out]. */
    private fun appendPcm16AsMonoFloat(buffer: ByteBuffer, channelCount: Int, out: MutableList<Float>) {
        val shortBuffer = buffer.asShortBuffer()
        val total = shortBuffer.remaining()
        val frames = total / channelCount
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += shortBuffer.get(idx) / 32768f
                idx++
            }
            out.add(sum / channelCount)
        }
    }

    /** Crude block-average decimation from [fromRate] to [toRate] — fine for tempo/tonality analysis, not for playback. */
    private fun downsample(input: List<Float>, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate <= toRate || fromRate <= 0) {
            return input.toFloatArray()
        }
        val ratio = fromRate.toFloat() / toRate.toFloat()
        val outSize = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val start = (i * ratio).toInt()
            val end = min(input.size, ((i + 1) * ratio).toInt().coerceAtLeast(start + 1))
            var sum = 0f
            var count = 0
            for (j in start until end) {
                sum += input[j]
                count++
            }
            out[i] = if (count > 0) sum / count else 0f
        }
        return out
    }

    // endregion

    // region Feature extraction

    private fun computeFeatures(pcm: FloatArray): RawAudioFeatures {
        val frames = frameSignal(pcm, frameSize, hopSize)

        val frameRms = FloatArray(frames.size)
        val centroids = FloatArray(frames.size)
        val chroma = FloatArray(12)

        var overallSumSquares = 0.0
        for (s in pcm) overallSumSquares += (s * s).toDouble()
        val overallRms = sqrt(overallSumSquares / pcm.size).toFloat()

        for ((i, frame) in frames.withIndex()) {
            var sumSq = 0f
            for (s in frame) sumSq += s * s
            frameRms[i] = sqrt(sumSq / frame.size)

            val windowed = frame.copyOf()
            applyHannWindow(windowed)
            val mags = SimpleFFT.magnitudeSpectrum(windowed)

            centroids[i] = spectralCentroid(mags, analysisSampleRate, frameSize)
            accumulateChroma(mags, analysisSampleRate, frameSize, chroma)
        }

        val brightness = if (centroids.isNotEmpty()) {
            (centroids.average() / (analysisSampleRate / 2.0)).toFloat().coerceIn(0f, 1f)
        } else 0f

        val (tempoBpm, onsetDensity) = estimateTempoAndOnsetDensity(
            frameRms,
            frameDurationSeconds = hopSize.toFloat() / analysisSampleRate
        )

        val tonality = estimateTonality(chroma)

        return RawAudioFeatures(
            tempoBpm = tempoBpm,
            rmsLoudness = overallRms,
            onsetDensity = onsetDensity,
            brightness = brightness,
            tonality = tonality
        )
    }

    private fun frameSignal(pcm: FloatArray, frameSize: Int, hopSize: Int): List<FloatArray> {
        val frames = ArrayList<FloatArray>()
        var start = 0
        while (start + frameSize <= pcm.size) {
            frames.add(pcm.copyOfRange(start, start + frameSize))
            start += hopSize
        }
        if (frames.isEmpty() && pcm.size >= frameSize / 2) {
            // Very short sample: pad the one frame we can get with zeros rather than skipping analysis.
            val padded = FloatArray(frameSize)
            pcm.copyInto(padded, 0, 0, pcm.size)
            frames.add(padded)
        }
        return frames
    }

    private fun spectralCentroid(mags: FloatArray, sampleRate: Int, fftSize: Int): Float {
        var weightedSum = 0.0
        var magSum = 0.0
        for (bin in mags.indices) {
            val freq = bin.toDouble() * sampleRate / fftSize
            weightedSum += freq * mags[bin]
            magSum += mags[bin]
        }
        return if (magSum > 1e-9) (weightedSum / magSum).toFloat() else 0f
    }

    /** Folds FFT magnitude bins into 12 pitch classes (equal temperament, A4=440Hz), accumulating into [chroma]. */
    private fun accumulateChroma(mags: FloatArray, sampleRate: Int, fftSize: Int, chroma: FloatArray) {
        val minFreq = 80.0
        val maxFreq = 5000.0
        for (bin in mags.indices) {
            val freq = bin.toDouble() * sampleRate / fftSize
            if (freq < minFreq || freq > maxFreq) continue
            val midi = 69.0 + 12.0 * log2(freq / 440.0)
            val pitchClass = (((midi.roundToInt()) % 12) + 12) % 12
            chroma[pitchClass] += mags[bin]
        }
    }

    /** Krumhansl-Schmuckler key profiles. */
    private val majorProfile = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
    )
    private val minorProfile = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
    )

    private fun estimateTonality(chroma: FloatArray): Float {
        val maxVal = chroma.maxOrNull() ?: 0f
        if (maxVal <= 1e-9f) return 0f
        val normalizedChroma = FloatArray(12) { chroma[it] / maxVal }

        var bestMajor = -1f
        var bestMinor = -1f
        for (rotation in 0 until 12) {
            bestMajor = max(bestMajor, pearsonCorrelation(normalizedChroma, majorProfile, rotation))
            bestMinor = max(bestMinor, pearsonCorrelation(normalizedChroma, minorProfile, rotation))
        }
        return (bestMajor - bestMinor).coerceIn(-1f, 1f)
    }

    companion object {
        /** Default snippet length in seconds when the user hasn't chosen otherwise. */
        const val DEFAULT_SNIPPET_SECONDS = 25

        /** Value of [snippetSeconds] meaning "analyze the whole track" instead of a short window. */
        const val FULL_TRACK = 0

        /** Default start offset for a partial snippet — skips the first 30% of the track (the previous hardcoded behavior). */
        const val DEFAULT_START_OFFSET_FRACTION = 0.3f
    }

    private fun pearsonCorrelation(chroma: FloatArray, profile: FloatArray, rotation: Int): Float {
        val n = 12
        val rotatedProfile = FloatArray(n) { profile[(it + rotation) % n] }
        val meanA = chroma.average()
        val meanB = rotatedProfile.average()
        var num = 0.0
        var denomA = 0.0
        var denomB = 0.0
        for (i in 0 until n) {
            val da = chroma[i] - meanA
            val db = rotatedProfile[i] - meanB
            num += da * db
            denomA += da * da
            denomB += db * db
        }
        val denom = sqrt(denomA * denomB)
        return if (denom > 1e-9) (num / denom).toFloat() else 0f
    }

    /**
     * Autocorrelates a frame-energy onset envelope to estimate tempo in the 50-200 BPM range,
     * and separately counts novelty peaks per second as a rhythmic-density proxy.
     */
    private fun estimateTempoAndOnsetDensity(
        frameRms: FloatArray,
        frameDurationSeconds: Float
    ): Pair<Float, Float> {
        if (frameRms.size < 4) return 60f to 0f

        // Half-wave rectified novelty (onset strength envelope).
        val novelty = FloatArray(frameRms.size)
        for (i in 1 until frameRms.size) {
            novelty[i] = max(0f, frameRms[i] - frameRms[i - 1])
        }

        val mean = novelty.average().toFloat()
        val variance = novelty.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / novelty.size
        val std = sqrt(variance)
        val centered = FloatArray(novelty.size) { novelty[it] - mean }

        val minBpm = 50.0
        val maxBpm = 200.0
        val minLag = max(1, (60.0 / (maxBpm * frameDurationSeconds)).roundToInt())
        val maxLag = min(centered.size - 1, (60.0 / (minBpm * frameDurationSeconds)).roundToInt())

        var bestLag = minLag
        var bestScore = Float.NEGATIVE_INFINITY
        if (maxLag > minLag) {
            for (lag in minLag..maxLag) {
                var score = 0f
                for (i in 0 until centered.size - lag) {
                    score += centered[i] * centered[i + lag]
                }
                if (score > bestScore) {
                    bestScore = score
                    bestLag = lag
                }
            }
        }

        val tempoBpm = (60.0 / (bestLag * frameDurationSeconds)).toFloat().coerceIn(50f, 200f)

        val threshold = mean + std * 0.5f
        val peakCount = novelty.count { it > threshold }
        val totalSeconds = (frameRms.size * frameDurationSeconds).coerceAtLeast(0.001f)
        val onsetDensity = peakCount / totalSeconds

        return tempoBpm to onsetDensity
    }

    // endregion
}
