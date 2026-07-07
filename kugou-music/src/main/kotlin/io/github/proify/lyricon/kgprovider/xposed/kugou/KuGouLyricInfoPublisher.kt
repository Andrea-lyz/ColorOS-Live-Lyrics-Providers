/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale

object KuGouLyricInfoPublisher {
    private const val TAG = "KuGouProvider"
    private const val METADATA_KEY_LYRIC_INFO = "lyricInfo"
    private const val KUGOU_DIAGNOSTICS_ENABLED = false
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val lock = Any()

    @Volatile
    private var selfPublishing = false
    private var lastSession: MediaSession? = null
    private var lastMetadata: MediaMetadata? = null
    private var latestMeta: MetadataData? = null
    private var latestSong: Song? = null
    private var latestGeneration = 0L
    private var latestPackageName: String? = null
    private var lastPublishedFingerprint = ""

    fun isSelfPublishing(): Boolean = selfPublishing

    fun onMetadata(session: MediaSession, metadata: MediaMetadata) {
        if (selfPublishing) return
        synchronized(lock) {
            lastSession = session
            lastMetadata = metadata
        }
        tryPublish("metadata")
    }

    fun onTrackChanged(meta: MetadataData, generation: Long) {
        synchronized(lock) {
            latestMeta = meta
            latestSong = null
            latestGeneration = generation
            lastPublishedFingerprint = ""
        }
    }

    fun onLyricReady(
        context: Context?,
        song: Song,
        meta: MetadataData,
        generation: Long
    ) {
        synchronized(lock) {
            latestMeta = meta
            latestSong = song
            latestGeneration = generation
            latestPackageName = context?.packageName
        }
        tryPublish("lyric-ready", context)
    }

    private fun tryPublish(reason: String, context: Context? = null) {
        val request = synchronized(lock) {
            val session = lastSession ?: return
            val metadata = lastMetadata ?: return
            val meta = latestMeta ?: return
            val song = latestSong ?: return
            if (!matchesCurrentTrack(metadata, meta, song)) {
                return
            }

            val packageName = context?.packageName ?: latestPackageName
            val lyricInfo = SaltLyricBridge.buildLyricInfoForPackage(
                packageName,
                song,
                latestGeneration
            ) ?: return
            val fingerprint = buildFingerprint(meta, lyricInfo)
            if (fingerprint == lastPublishedFingerprint) return

            PublishRequest(
                session = session,
                metadata = buildPatchedMetadata(metadata, meta, lyricInfo),
                fingerprint = fingerprint,
                lyricInfoChars = lyricInfo.length,
                title = meta.title,
                reason = reason
            )
        }

        runCatching {
            selfPublishing = true
            request.session.setMetadata(request.metadata)
        }.onSuccess {
            synchronized(lock) {
                lastPublishedFingerprint = request.fingerprint
            }
            diagnose(
                "KG_DIAG published MediaSession lyricInfo reason=${request.reason} " +
                    "title=${request.title.take(64)} chars=${request.lyricInfoChars}"
            )
        }.onFailure {
            YLog.error(tag = TAG, msg = "Failed to publish KuGou lyricInfo: ${it.message}")
        }.also {
            selfPublishing = false
        }
    }

    private fun matchesCurrentTrack(
        metadata: MediaMetadata,
        meta: MetadataData,
        song: Song
    ): Boolean {
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        if (mediaId.isNotBlank() && meta.identityKeys.contains(mediaId)) return true

        val mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI).orEmpty()
        if (mediaUri.isNotBlank() && meta.identityKeys.contains(mediaUri)) return true

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return normalizeTrackComponent(title) == normalizeTrackComponent(song.name) &&
            normalizeTrackComponent(artist) == normalizeTrackComponent(song.artist)
    }

    private fun buildPatchedMetadata(
        source: MediaMetadata,
        meta: MetadataData,
        lyricInfo: String
    ): MediaMetadata {
        return MediaMetadata.Builder(source)
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, meta.artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, meta.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.album)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, meta.identityId)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, meta.mediaUri)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.duration)
            .putString(METADATA_KEY_LYRIC_INFO, lyricInfo)
            .build()
    }

    private fun buildFingerprint(meta: MetadataData, lyricInfo: String): String {
        return meta.identityId + ':' + meta.trackKey + ':' + lyricInfo.hashCode()
    }

    private fun normalizeTrackComponent(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
    }

    private fun diagnose(message: String) {
        if (KUGOU_DIAGNOSTICS_ENABLED || Log.isLoggable(TAG, Log.DEBUG)) {
            YLog.debug(tag = TAG, msg = message)
        }
    }

    private data class PublishRequest(
        val session: MediaSession,
        val metadata: MediaMetadata,
        val fingerprint: String,
        val lyricInfoChars: Int,
        val title: String,
        val reason: String
    )
}
