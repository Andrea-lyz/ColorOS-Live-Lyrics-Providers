package io.github.proify.lyricon.qishuiprovider.xposed

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.util.LinkedHashMap

internal object QiShuiLog {
    private const val ROOT_TAG = "QiShui"
    private const val MAX_MESSAGE_LENGTH = 1_024
    private const val WARNING_THROTTLE_MS = 60_000L
    private const val WARNING_CACHE_MAX_ENTRIES = 64

    private val lastWarningAtByKey = object : LinkedHashMap<String, Long>(
        WARNING_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Long>?
        ): Boolean = size > WARNING_CACHE_MAX_ENTRIES
    }

    fun debug(message: String, tag: String = ROOT_TAG) {
        if (isDebugEnabled(tag)) Log.d(tag, normalize(message))
    }

    fun isDebugEnabled(tag: String = ROOT_TAG): Boolean {
        return Log.isLoggable(ROOT_TAG, Log.DEBUG) ||
            (tag != ROOT_TAG && Log.isLoggable(tag, Log.DEBUG))
    }

    fun warning(
        message: String,
        throwable: Throwable? = null,
        tag: String = ROOT_TAG
    ) {
        val normalized = normalize(message)
        if (throwable == null) Log.w(tag, normalized) else Log.w(tag, normalized, throwable)
        mirrorToLsposed(tag, "W", normalized, throwable)
    }

    fun warningOnce(
        key: String,
        message: String,
        throwable: Throwable? = null,
        tag: String = ROOT_TAG,
        nowElapsedMillis: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        synchronized(lastWarningAtByKey) {
            val last = lastWarningAtByKey[key]
            if (last != null && nowElapsedMillis - last < WARNING_THROTTLE_MS) return
            lastWarningAtByKey[key] = nowElapsedMillis
        }
        warning(message, throwable, tag)
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
        tag: String = ROOT_TAG
    ) {
        val normalized = normalize(message)
        if (throwable == null) Log.e(tag, normalized) else Log.e(tag, normalized, throwable)
        mirrorToLsposed(tag, "E", normalized, throwable)
    }

    private fun mirrorToLsposed(
        tag: String,
        level: String,
        message: String,
        throwable: Throwable?
    ) {
        runCatching {
            XposedBridge.log("[$tag][$level] $message")
            throwable?.let(XposedBridge::log)
        }
    }

    private fun normalize(message: String): String {
        val normalized = buildString(message.length.coerceAtMost(MAX_MESSAGE_LENGTH)) {
            message.forEach { character ->
                when (character) {
                    '\r', '\n', '\t' -> append(' ')
                    else -> if (!character.isISOControl()) append(character)
                }
                if (length >= MAX_MESSAGE_LENGTH) return@buildString
            }
        }.trim()
        return if (message.length > MAX_MESSAGE_LENGTH) "$normalized…" else normalized
    }
}
