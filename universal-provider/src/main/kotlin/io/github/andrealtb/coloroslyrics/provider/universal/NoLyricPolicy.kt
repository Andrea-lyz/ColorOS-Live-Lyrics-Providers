package io.github.andrealtb.coloroslyrics.provider.universal

object NoLyricPolicy {
    const val REASON_USER = "userConfirmed"
    const val REASON_MISS = "confirmedMiss"
    const val REASON_CREDITS = "creditsOnly"

    /** In-memory marker for "this fetch found nothing"; never a persisted negative result. */
    const val STATUS_MISS = "miss"

    enum class Outcome {
        PUBLISH_LYRICS,

        /** The catalogues agree this is a pure-music track; the negative result may be cached. */
        AUTO_NEGATIVE,

        /** Nothing matched this time. Retrying later is allowed, so it must never be persisted. */
        AUTO_MISS,

        TRANSIENT_RETRY
    }

    fun decide(hasUsableLyric: Boolean, instrumental: Boolean, transientFailure: Boolean): Outcome {
        if (hasUsableLyric && !instrumental) return Outcome.PUBLISH_LYRICS
        // A credits-only answer is evidence about the track; a plain miss is not.
        if (instrumental) return Outcome.AUTO_NEGATIVE
        if (transientFailure) return Outcome.TRANSIENT_RETRY
        return Outcome.AUTO_MISS
    }

    fun autoReason(instrumental: Boolean): String =
        if (instrumental) REASON_CREDITS else REASON_MISS

    fun isNoLyric(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return json.contains("\"noLyric\":true")
    }

    fun isUserConfirmed(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return json.contains("\"noLyricReason\":\"$REASON_USER\"") ||
            (isNoLyric(json) && json.contains("userConfirmed"))
    }

    /**
     * Older builds persisted an automatic miss as a negative result. Such an entry must stay
     * retryable, otherwise one bad first fetch denies the song its lyrics forever.
     */
    fun isRetryableMiss(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return json.contains("\"noLyricReason\":\"$REASON_MISS\"") ||
            json.contains("\"lyricStatus\":\"$STATUS_MISS\"")
    }

    fun shouldSkipNetwork(json: String?): Boolean = isNoLyric(json) && !isRetryableMiss(json)

    fun payloadJson(title: String, artist: String, reason: String): String =
        org.json.JSONObject().apply {
            put("songName", title)
            put("artist", artist)
            put("noLyric", true)
            put("lyric", "")
            put("noLyricReason", reason)
            put("provider", "io.github.andrealtb.coloroslyrics.provider.universal")
            put("remark", "[CLLUniversal]")
        }.toString()

    /**
     * A miss is a statement about one attempt, not about the track: it keeps the session honest
     * (the UI reports a failure) while leaving a later callback with fuller metadata free to retry.
     */
    fun missPayloadJson(title: String, artist: String, durationMs: Long? = null): String =
        org.json.JSONObject().apply {
            put("songName", title)
            put("artist", artist)
            put("durationMs", durationMs ?: 0L)
            put("noLyric", false)
            put("lyric", "")
            put("lyricStatus", STATUS_MISS)
            put("provider", "io.github.andrealtb.coloroslyrics.provider.universal")
            put("remark", "[CLLUniversal]")
        }.toString()
}
