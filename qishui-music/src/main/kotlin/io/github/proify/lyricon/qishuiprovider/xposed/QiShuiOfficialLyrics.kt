package io.github.proify.lyricon.qishuiprovider.xposed

import android.os.SystemClock
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.qishuiprovider.xposed.parser.NetResponseCache
import io.github.proify.lyricon.qishuiprovider.xposed.parser.toRichLyric
import java.util.LinkedHashMap

object QiShuiOfficialLyrics {
    private const val TAG = "QiShuiOfficialLyrics"
    private const val LOG_THROTTLE_MS = 10_000L
    private var installed = false
    private var onSong: ((Song, String) -> Unit)? = null

    private val lastLogAtByKey = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 64
        }
    }

    fun install(loader: ClassLoader?, onSong: (Song, String) -> Unit) {
        if (loader == null || installed) return
        installed = true
        this.onSong = onSong
        hookCoreRemoteControl(loader)
    }

    private fun hookCoreRemoteControl(loader: ClassLoader) {
        val clazz = runCatching {
            XposedHelpers.findClass(
                "com.luna.biz.playing.player.remote.control.CoreRemoteControl",
                loader
            )
        }.getOrNull() ?: return logOnce(
            "missing-core",
            "CoreRemoteControl class missing"
        )

        val methods = clazz.declaredMethods
            .filter { it.name == "update" && it.parameterTypes.size >= 2 }
        if (methods.isEmpty()) {
            logOnce("missing-update", "CoreRemoteControl.update method missing")
            return
        }

        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val playable = resolvePlayable(param.args.firstOrNull()) ?: return
                    val song = buildSong(playable) ?: return
                    logDebugOnce(
                        "official:${song.id.orEmpty()}",
                        "official lyric ready, mediaId=${song.id.orEmpty()}, " +
                            "title=${song.name.orEmpty()}, lyrics=${song.lyrics?.size ?: 0}"
                    )
                    onSong?.invoke(song, "core-remote-control")
                }
            })
        }
        YLog.info(tag = TAG, msg = "CoreRemoteControl official lyric hooked, methods=${methods.size}")
    }

    private fun buildSong(playable: Any): Song? {
        val id = playableId(playable)?.takeIf { it.isNotBlank() } ?: return null
        val trackLyric = callNoArg(playable, "getLyric") ?: return null
        val lyric = buildNetLyric(trackLyric) ?: return null
        val lyrics = NetResponseCache(lyric).toRichLyric()
        if (lyrics.isEmpty()) return null

        val metadata = MetadataCache.get(id)
        return Song(
            id = id,
            name = metadata?.title?.takeIf { it.isNotBlank() } ?: playableTitle(playable),
            artist = metadata?.artist?.takeIf { it.isNotBlank() } ?: playableArtist(playable),
            duration = validDuration(metadata?.duration ?: playableDuration(playable)),
            lyrics = lyrics
        )
    }

    private fun buildNetLyric(trackLyric: Any): NetResponseCache.Lyric? {
        val type = firstNonBlank(
            callNoArg(trackLyric, "getType")?.toString(),
            readField(trackLyric, "type")?.toString()
        )
        val content = firstNonBlank(
            callNoArg(trackLyric, "getContent")?.toString(),
            callNoArg(trackLyric, "getLyric")?.toString(),
            readField(trackLyric, "content")?.toString(),
            readField(trackLyric, "lyric")?.toString()
        )
        if (type.isNullOrBlank() || content.isNullOrBlank()) return null

        return NetResponseCache.Lyric(
            type = type,
            content = content,
            lang_translations = readTranslations(trackLyric)
        )
    }

    private fun readTranslations(trackLyric: Any): Map<String, NetResponseCache.Translation>? {
        val value = firstNonNull(
            callNoArg(trackLyric, "getLangTranslations"),
            callNoArg(trackLyric, "getLang_translations"),
            readField(trackLyric, "langTranslations"),
            readField(trackLyric, "lang_translations")
        ) as? Map<*, *> ?: return null

        return value.mapNotNull { (key, item) ->
            val lang = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = firstNonBlank(
                callNoArg(item, "getType")?.toString(),
                readField(item, "type")?.toString()
            )
            val content = firstNonBlank(
                callNoArg(item, "getContent")?.toString(),
                callNoArg(item, "getLyric")?.toString(),
                readField(item, "content")?.toString(),
                readField(item, "lyric")?.toString()
            ) ?: return@mapNotNull null
            lang to NetResponseCache.Translation(
                content = content,
                type = type
            )
        }.toMap().takeIf { it.isNotEmpty() }
    }

    private fun resolvePlayable(context: Any?): Any? {
        if (context == null) return null
        return listOf("d", "getPlayable", "getCurrentPlayable", "getCurrentQueueItem")
            .firstNotNullOfOrNull { name -> callNoArg(context, name) }
    }

    private fun playableId(playable: Any): String? {
        return listOf(
            "getId",
            "getPlayableId",
            "getMediaId",
            "getTrackId",
            "getItemId"
        ).firstNotNullOfOrNull { name ->
            callNoArg(playable, name)?.toString()?.takeIf { it.isNotBlank() }
        } ?: listOf("id", "trackId", "mediaId", "itemId").firstNotNullOfOrNull { name ->
            readField(playable, name)?.toString()?.takeIf { it.isNotBlank() }
        }
    }

    private fun playableTitle(playable: Any): String {
        return firstNonBlank(
            callNoArg(playable, "getName")?.toString(),
            callNoArg(playable, "getTitle")?.toString(),
            readField(playable, "name")?.toString(),
            readField(playable, "title")?.toString()
        ).orEmpty()
    }

    private fun playableArtist(playable: Any): String {
        firstNonBlank(
            callNoArg(playable, "getArtistName")?.toString(),
            callNoArg(playable, "getArtistText")?.toString(),
            readField(playable, "artistName")?.toString(),
            readField(playable, "artistText")?.toString()
        )?.let { return it }

        val artists = firstNonNull(
            callNoArg(playable, "getArtists"),
            readField(playable, "artists")
        ) as? Collection<*> ?: return ""
        return artists.mapNotNull { artist ->
            firstNonBlank(
                callNoArg(artist, "getName")?.toString(),
                callNoArg(artist, "getSimpleDisplayName")?.toString(),
                readField(artist, "name")?.toString(),
                readField(artist, "simple_display_name")?.toString()
            )
        }.distinct().joinToString("/")
    }

    private fun playableDuration(playable: Any): Long {
        return listOf("getDuration", "getDurationMs", "duration", "durationMs")
            .firstNotNullOfOrNull { name ->
                val value = if (name.startsWith("get")) {
                    callNoArg(playable, name)
                } else {
                    readField(playable, name)
                }
                (value as? Number)?.toLong()?.takeIf { it > 0L }
            } ?: 0L
    }

    private fun callNoArg(instance: Any?, name: String): Any? {
        if (instance == null) return null
        return runCatching {
            val method = instance.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: instance.javaClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: return null
            method.isAccessible = true
            method.invoke(instance)
        }.getOrNull()
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            runCatching {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field.get(instance)
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun firstNonNull(vararg values: Any?): Any? =
        values.firstOrNull { it != null }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun validDuration(duration: Long): Long =
        if (duration in 1L..24L * 60L * 60L * 1000L) duration else 0L

    private fun logOnce(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastLogAtByKey) {
            val last = lastLogAtByKey[key]
            if (last != null && now - last < LOG_THROTTLE_MS) return
            lastLogAtByKey[key] = now
        }
        YLog.info(tag = TAG, msg = message)
    }

    private fun logDebugOnce(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastLogAtByKey) {
            val last = lastLogAtByKey[key]
            if (last != null && now - last < LOG_THROTTLE_MS) return
            lastLogAtByKey[key] = now
        }
        YLog.debug(tag = TAG, msg = message)
    }
}
