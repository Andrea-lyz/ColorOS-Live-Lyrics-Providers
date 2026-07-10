/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.content.Context
import android.media.MediaMetadata
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import java.lang.reflect.Field
import kotlin.math.max

object PowerampInternalProbe : YukiBaseHooker() {
    private const val TAG = "PowerampInternalProbe"
    private const val LYRIC_INFO_KEY = "lyricInfo"
    private const val POWERAMP_OBFUSCATED_PACKAGE = "\u05c5"
    private val WHITESPACE_REGEX = Regex("\\s+")

    @Volatile
    private var lastMetadataFingerprint = ""

    override fun onHook() = Unit

    fun install(loader: ClassLoader?) {
        if (!PowerampLog.isDebugEnabled(TAG) &&
            !PowerampLog.isDebugEnabled(Constants.LOG_TAG)
        ) {
            return
        }
        hookMediaMetadata()
        if (loader == null) {
            PowerampLog.debug(tag = TAG, msg = "Skip Poweramp internal probe: appClassLoader is null")
            return
        }
        hookLyricsChain(loader)
        hookResolverSteps(loader)
        hookLrcParser(loader)
    }

    private fun hookMediaMetadata() {
        runCatching {
            "android.media.session.MediaSession".toClass()
                .resolve()
                .firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
                        logMediaMetadata(metadata)
                    }
                }
        }.onSuccess {
            PowerampLog.info(tag = TAG, msg = "Hooked MediaSession.setMetadata probe")
        }.onFailure {
            PowerampLog.error(tag = TAG, msg = "Failed to hook MediaSession.setMetadata probe", e = it)
        }
    }

    private fun hookLyricsChain(loader: ClassLoader) {
        runCatching {
            val lyricsChainClass = resolvePowerampClassName(loader, "o70")
            val chainArgsClass = resolvePowerampClassName(loader, "q70")
            lyricsChainClass.toClass(loader)
                .resolve()
                .firstMethod {
                    name = "K"
                    parameters(chainArgsClass)
                }.hook {
                    after {
                        val chainArgs = args.getOrNull(0) ?: return@after
                        PowerampLog.debug(
                            tag = TAG,
                            msg = "LyricsChain.K finished: ${describeChainArgs(chainArgs)}"
                        )
                    }
                }
        }.onSuccess {
            PowerampLog.info(tag = TAG, msg = "Hooked Poweramp LyricsChain.K probe")
        }.onFailure {
            PowerampLog.error(
                tag = TAG,
                msg = "Failed to hook Poweramp LyricsChain.K probe, candidates=${classCandidates("o70")}",
                e = it
            )
        }
    }

    private fun hookResolverSteps(loader: ClassLoader) {
        runCatching {
            val resolverClass = resolvePowerampClassName(loader, "n70")
            resolverClass.toClass(loader)
                .resolve()
                .firstMethod {
                    name = "invoke"
                    parameters(Any::class.java, Any::class.java, Any::class.java)
                }.hook {
                    after {
                        val chainArgs = args.getOrNull(1)
                        val resolveCtx = args.getOrNull(2)
                        PowerampLog.debug(
                            tag = TAG,
                            msg = "LyricsResolver result=${this.result}, " +
                                "args=${describeChainArgs(chainArgs)}, ctx=${describeResolveCtx(resolveCtx)}"
                        )
                    }
                }
        }.onSuccess {
            PowerampLog.info(tag = TAG, msg = "Hooked Poweramp LyricsResolver steps probe")
        }.onFailure {
            PowerampLog.error(
                tag = TAG,
                msg = "Failed to hook Poweramp LyricsResolver steps probe, candidates=${classCandidates("n70")}",
                e = it
            )
        }
    }

    private fun hookLrcParser(loader: ClassLoader) {
        runCatching {
            val parserClass = resolvePowerampClassName(loader, "h70")
            val streamClass = resolvePowerampClassName(loader, "xy0")
            parserClass.toClass(loader)
                .resolve()
                .firstMethod {
                    name = "B"
                    parameters(
                        Context::class.java,
                        streamClass,
                        String::class.java,
                        Boolean::class.java,
                        Boolean::class.java,
                        Boolean::class.java
                    )
                }.hook {
                    after {
                        val path = args.getOrNull(2) as? String
                        PowerampLog.debug(
                            tag = TAG,
                            msg = "LrcParser.B path=${shorten(path.orEmpty())}, result=${describeLyrics(this.result)}"
                        )
                    }
                }
        }.onSuccess {
            PowerampLog.info(tag = TAG, msg = "Hooked Poweramp LRC parser probe")
        }.onFailure {
            PowerampLog.error(
                tag = TAG,
                msg = "Failed to hook Poweramp LRC parser probe, candidates=${classCandidates("h70")}",
                e = it
            )
        }
    }

    private fun resolvePowerampClassName(loader: ClassLoader, simpleName: String): String {
        return classCandidates(simpleName).firstOrNull { className ->
            runCatching { Class.forName(className, false, loader) }.isSuccess
        } ?: error("Poweramp class not found: ${classCandidates(simpleName).joinToString()}")
    }

    private fun classCandidates(simpleName: String): List<String> =
        listOf("$POWERAMP_OBFUSCATED_PACKAGE.$simpleName", "p000.$simpleName")

    private fun logMediaMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        val displayIconUri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val lyricInfo = metadata.getText(LYRIC_INFO_KEY)?.toString()
        val hasArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
        val fingerprint = listOf(title, artist, album, mediaId, duration, lyricInfo?.length).joinToString("|")
        if (fingerprint == lastMetadataFingerprint) return
        lastMetadataFingerprint = fingerprint

        PowerampLog.debug(
            tag = TAG,
            msg = "MediaSession metadata: mediaId=${mediaId.orEmpty()}, title=${shorten(title.orEmpty())}, " +
                "artist=${shorten(artist.orEmpty())}, album=${shorten(album.orEmpty())}, duration=$duration, " +
                "lyricInfoChars=${lyricInfo?.length ?: 0}, hasArtBitmap=$hasArtBitmap, " +
                "artUri=${shorten(artUri.orEmpty())}, displayIconUri=${shorten(displayIconUri.orEmpty())}"
        )
    }

    private fun describeChainArgs(value: Any?): String {
        if (value == null) return "null"
        val trackId = readField(value, "c")
        val serial = readField(value, "b")
        val firstQuery = readField(value, "a")
        val state = readField(value, "g")
        val lyrics = readField(value, "f")
        return "fileId=$trackId, serial=$serial, firstQuery=$firstQuery, state=$state, ${describeLyrics(lyrics)}"
    }

    private fun describeResolveCtx(value: Any?): String {
        if (value == null) return "null"
        val trackId = readField(value, "f3552")
        val hasLyricsTag = readField(value, "f3551")
        val lrcFilesId = readField(value, "A")
        val cachedLyricsId = readField(value, "f3560")
        val lrcPath = readField(value, "y") as? String
        val lrcIsUtf8 = readField(value, "x")
        val cachedContent = readField(value, "f3559") as? String
        val pluginPak = readField(value, "K") as? String
        val pluginInfoLine = readField(value, "f3554") as? String
        val titleTag = readField(value, "H") as? String
        val artistTag = readField(value, "P") as? String
        val lyrics = readField(value, "f3555")
        return "trackId=$trackId, hasLyricsTag=$hasLyricsTag, lrcFilesId=$lrcFilesId, " +
            "cachedLyricsId=$cachedLyricsId, lrcIsUtf8=$lrcIsUtf8, lrcPath=${shorten(lrcPath.orEmpty())}, " +
            "cachedChars=${cachedContent?.length ?: 0}, cachedHead=${shorten(cachedContent.orEmpty())}, " +
            "pluginPak=${shorten(pluginPak.orEmpty())}, infoLine=${shorten(pluginInfoLine.orEmpty())}, " +
            "titleTag=${shorten(titleTag.orEmpty())}, artistTag=${shorten(artistTag.orEmpty())}, " +
            describeLyrics(lyrics)
    }

    private fun describeLyrics(value: Any?): String {
        if (value == null) return "lyrics=null"
        val lines = readField(value, "f3020") as? List<*>
        val title = readField(value, "A") as? String
        val artist = readField(value, "f3019") as? String
        val album = readField(value, "B") as? String
        val sync = readField(value, "X")
        val lengthMs = readField(value, "f3022")
        val offsetMs = readField(value, "f3021")
        val firstLine = describeLine(lines?.firstOrNull())
        val secondLine = describeLine(lines?.drop(1)?.firstOrNull())
        return "lyricsLines=${lines?.size ?: 0}, sync=$sync, lengthMs=$lengthMs, offsetMs=$offsetMs, " +
            "title=${shorten(title.orEmpty())}, artist=${shorten(artist.orEmpty())}, album=${shorten(album.orEmpty())}, " +
            "first=$firstLine, second=$secondLine"
    }

    private fun describeLine(value: Any?): String {
        if (value == null) return "null"
        val time = readField(value, "f5251")
        val text = readField(value, "B") as? String
        return "${time}ms:${shorten(text.orEmpty())}"
    }

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = findDeclaredField(type, name)
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun findDeclaredField(type: Class<*>, name: String): Field? =
        runCatching { type.getDeclaredField(name) }.getOrNull()

    private fun shorten(value: String, maxLength: Int = 96): String {
        val clean = value.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        if (clean.length <= maxLength) return clean
        val end = max(0, maxLength - 3)
        return clean.substring(0, end) + "..."
    }
}
