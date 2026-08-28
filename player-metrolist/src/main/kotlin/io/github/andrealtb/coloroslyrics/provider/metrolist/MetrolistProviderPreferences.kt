/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.metrolist

import android.content.Context
import java.io.File

/** Reads Metrolist's provider order without creating a second DataStore instance. */
internal object MetrolistProviderPreferences {
    const val DATASTORE_RELATIVE_PATH = "datastore/settings.preferences_pb"
    private const val PROVIDER_ORDER_KEY = "lyricsProviderOrder"

    private val hostDefaultOrder = listOf(
        "BetterLyrics",
        "LrcLib",
        "KuGou",
        "Paxsenix",
        "LyricsPlus",
        "YouTubeSubtitle",
        "YouTube"
    )
    private val knownProviders = hostDefaultOrder.toSet()
    private val providerEnableKeys = mapOf(
        "BetterLyrics" to "enableBetterLyrics",
        "LrcLib" to "enableLrclib",
        "KuGou" to "enableKugou",
        "Paxsenix" to "enablePaxsenix",
        "LyricsPlus" to "enableLyricsPlus"
    )

    data class Snapshot(
        val strings: Map<String, String>,
        val booleans: Map<String, Boolean>
    )

    data class Selection(
        val order: List<String>,
        val enabled: Map<String, Boolean>,
        val source: String
    )

    fun read(context: Context): Selection {
        val file = File(context.filesDir, DATASTORE_RELATIVE_PATH)
        if (!file.exists()) {
            return Selection(hostDefaultOrder, emptyMap(), "default:file-missing")
        }

        return try {
            val preferences = parse(file.readBytes())
            val configuredOrder = preferences.strings[PROVIDER_ORDER_KEY]
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter { it in knownProviders }
            val order = configuredOrder.takeIf { it.isNotEmpty() } ?: hostDefaultOrder
            val enabled = providerEnableKeys.mapValues { (_, key) ->
                preferences.booleans[key] ?: true
            }
            val source = if (configuredOrder.isEmpty()) "default:key-missing" else "datastore"
            Selection(order, enabled, source)
        } catch (_: Exception) {
            Selection(hostDefaultOrder, emptyMap(), "default:parse-failed")
        }
    }

    /**
     * Decodes the string and boolean variants used by AndroidX Preferences DataStore.
     * PreferenceMap.preferences is field 1; Value.boolean is field 1 and Value.string is field 5.
     */
    fun parse(bytes: ByteArray): Snapshot {
        val strings = linkedMapOf<String, String>()
        val booleans = linkedMapOf<String, Boolean>()
        val root = ProtoReader(bytes)
        while (root.hasRemaining()) {
            val tag = root.readTag()
            if (tag.fieldNumber == 1 && tag.wireType == 2) {
                parseEntry(root.readLengthDelimited())?.let { (key, valueBytes) ->
                    parseValue(key, valueBytes, strings, booleans)
                }
            } else {
                root.skip(tag.wireType)
            }
        }
        return Snapshot(strings, booleans)
    }

    private fun parseEntry(bytes: ByteArray): Pair<String, ByteArray>? {
        val entry = ProtoReader(bytes)
        var key: String? = null
        var value: ByteArray? = null
        while (entry.hasRemaining()) {
            val tag = entry.readTag()
            when {
                tag.fieldNumber == 1 && tag.wireType == 2 ->
                    key = entry.readLengthDelimited().toString(Charsets.UTF_8)
                tag.fieldNumber == 2 && tag.wireType == 2 ->
                    value = entry.readLengthDelimited()
                else -> entry.skip(tag.wireType)
            }
        }
        return if (key != null && value != null) key to value else null
    }

    private fun parseValue(
        key: String,
        bytes: ByteArray,
        strings: MutableMap<String, String>,
        booleans: MutableMap<String, Boolean>
    ) {
        val value = ProtoReader(bytes)
        while (value.hasRemaining()) {
            val tag = value.readTag()
            when {
                tag.fieldNumber == 1 && tag.wireType == 0 ->
                    booleans[key] = value.readVarint() != 0L
                tag.fieldNumber == 5 && tag.wireType == 2 ->
                    strings[key] = value.readLengthDelimited().toString(Charsets.UTF_8)
                else -> value.skip(tag.wireType)
            }
        }
    }

    private data class ProtoTag(val fieldNumber: Int, val wireType: Int)

    private class ProtoReader(private val bytes: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < bytes.size

        fun readTag(): ProtoTag {
            val raw = readVarint().toInt()
            require(raw != 0) { "Invalid protobuf tag" }
            return ProtoTag(raw ushr 3, raw and 7)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < bytes.size) { "Truncated protobuf varint" }
                val value = bytes[position++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Oversized protobuf varint")
        }

        fun readLengthDelimited(): ByteArray {
            val length = readVarint().toInt()
            require(length >= 0 && position + length <= bytes.size) {
                "Invalid protobuf length $length"
            }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> advance(8)
                2 -> advance(readVarint().toInt())
                5 -> advance(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
            }
        }

        private fun advance(length: Int) {
            require(length >= 0 && position + length <= bytes.size) {
                "Invalid protobuf skip length $length"
            }
            position += length
        }
    }
}

