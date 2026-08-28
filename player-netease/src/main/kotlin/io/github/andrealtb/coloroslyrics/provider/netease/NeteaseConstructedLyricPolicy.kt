/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.proify.lyricon.yrckit.download.response.LyricResponse

/**
 * Pure policy for the NetEase 9.0.40 constructed-lyric path. The network
 * response is converted to the same snapshot consumed by the native append
 * decoder, while romaji remains deliberately outside the translation lane.
 */
object NeteaseConstructedLyricPolicy {

    data class Request(
        val musicId: Long,
        val track: TrackIdentity,
        val generation: Long
    ) {
        val key: String = "$musicId:$generation"
    }

    fun request(track: TrackIdentity, generation: Long): Request? {
        val musicId = track.id?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        if (generation <= 0L || track.title.isNullOrBlank()) return null
        return Request(musicId, track, generation)
    }

    fun snapshot(
        request: Request,
        response: LyricResponse
    ): NeteaseLyricInfoReader.Snapshot = NeteaseLyricInfoReader.Snapshot(
        track = request.track,
        lyricMusicId = request.musicId.toString(),
        lrc = response.lrc?.lyric,
        yrc = response.yrc?.lyric,
        lrcTranslate = response.tlyric?.lyric,
        yrcTranslate = response.ytlrc?.lyric
    )

    fun isCurrent(
        request: Request,
        currentTrack: TrackIdentity?,
        currentGeneration: Long
    ): Boolean = currentGeneration == request.generation &&
        currentTrack?.id == request.track.id
}
