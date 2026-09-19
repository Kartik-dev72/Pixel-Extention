package com.theveloper.pixelplay.data.audio

/**
 * Coarse genre -> mood prior lookup.
 *
 * Real audio analysis ([MoodAnalyzer]) is the primary signal for where a track sits on the
 * Music Square. This table exists for two supporting roles:
 *  - Give a track *some* sensible position immediately, before background analysis reaches it
 *    (or if analysis fails outright, e.g. DRM-protected/corrupt file).
 *  - Nudge the audio-derived position a little for genres whose typical mood is hard to read
 *    purely from a short local sample (e.g. spoken-word/podcast content reads acoustically
 *    "calm" but isn't really a mood-square candidate the same way a ballad is).
 *
 * Values are (energyPrior, valencePrior), each roughly -1..+1:
 *  - energyPrior: -1 calm ... +1 exciting
 *  - valencePrior: -1 sad ... +1 joyful
 */
internal object MoodGenrePriors {

    private val keywordPriors: List<Pair<String, Pair<Float, Float>>> = listOf(
        "metal" to (0.9f to -0.2f),
        "hardcore" to (0.95f to -0.3f),
        "punk" to (0.85f to 0.1f),
        "drum and bass" to (0.9f to 0.2f),
        "dnb" to (0.9f to 0.2f),
        "edm" to (0.85f to 0.5f),
        "techno" to (0.8f to -0.1f),
        "house" to (0.7f to 0.4f),
        "dance" to (0.8f to 0.5f),
        "trance" to (0.75f to 0.3f),
        "dubstep" to (0.85f to -0.1f),
        "rock" to (0.6f to 0.1f),
        "pop" to (0.4f to 0.5f),
        "reggaeton" to (0.6f to 0.5f),
        "latin" to (0.5f to 0.6f),
        "funk" to (0.55f to 0.5f),
        "disco" to (0.6f to 0.6f),
        "hip hop" to (0.5f to 0.0f),
        "hip-hop" to (0.5f to 0.0f),
        "rap" to (0.5f to -0.1f),
        "trap" to (0.55f to -0.2f),
        "reggae" to (0.2f to 0.4f),
        "folk" to (-0.2f to 0.2f),
        "country" to (0.1f to 0.3f),
        "jazz" to (-0.1f to 0.2f),
        "blues" to (-0.3f to -0.3f),
        "soul" to (-0.1f to 0.3f),
        "r&b" to (0.0f to 0.2f),
        "rnb" to (0.0f to 0.2f),
        "classical" to (-0.4f to 0.0f),
        "orchestra" to (-0.3f to 0.0f),
        "piano" to (-0.5f to 0.0f),
        "acoustic" to (-0.4f to 0.1f),
        "ballad" to (-0.5f to -0.2f),
        "ambient" to (-0.8f to -0.1f),
        "chill" to (-0.6f to 0.2f),
        "lo-fi" to (-0.6f to 0.1f),
        "lofi" to (-0.6f to 0.1f),
        "sleep" to (-0.9f to 0.0f),
        "meditation" to (-0.9f to 0.1f),
        "sad" to (-0.3f to -0.7f),
        "emo" to (0.1f to -0.6f),
        "gospel" to (0.2f to 0.6f),
        "christmas" to (0.1f to 0.6f),
        "k-pop" to (0.5f to 0.6f),
        "kpop" to (0.5f to 0.6f),
        "anime" to (0.3f to 0.4f),
        "soundtrack" to (-0.1f to 0.0f),
        "score" to (-0.2f to -0.1f),
        "podcast" to (-0.7f to 0.0f),
        "spoken" to (-0.7f to 0.0f)
    )

    /** Default prior for genres we don't recognize: dead center of the square. */
    val NEUTRAL = 0f to 0f

    /** Looks up a prior by substring match against a free-text genre tag (case-insensitive). */
    fun priorFor(genre: String?): Pair<Float, Float> {
        if (genre.isNullOrBlank()) return NEUTRAL
        val normalized = genre.trim().lowercase()
        for ((keyword, prior) in keywordPriors) {
            if (normalized.contains(keyword)) return prior
        }
        return NEUTRAL
    }
}
