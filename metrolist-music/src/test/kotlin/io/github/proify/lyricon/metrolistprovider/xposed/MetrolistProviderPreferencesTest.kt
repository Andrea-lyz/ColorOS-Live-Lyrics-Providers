/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.metrolistprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetrolistProviderPreferencesTest {
    @Test
    fun decodesProviderOrderAndEnableFlagsFromSettingsDataStore() {
        val bytes = preferenceMap(
            preferenceEntry(
                "lyricsProviderOrder",
                lengthDelimited(5, "KuGou,BetterLyrics,LrcLib".toByteArray())
            ),
            preferenceEntry("enableKugou", byteArrayOf(0x08, 0x00)),
            preferenceEntry("enableBetterLyrics", byteArrayOf(0x08, 0x01))
        )

        val preferences = MetrolistProviderPreferences.parse(bytes)

        assertEquals(
            "datastore/settings.preferences_pb",
            MetrolistProviderPreferences.DATASTORE_RELATIVE_PATH
        )
        assertEquals(
            "KuGou,BetterLyrics,LrcLib",
            preferences.strings["lyricsProviderOrder"]
        )
        assertEquals(false, preferences.booleans["enableKugou"])
        assertEquals(true, preferences.booleans["enableBetterLyrics"])
    }

    @Test
    fun rejectsTruncatedPreferenceMapInsteadOfGuessingValues() {
        val truncated = byteArrayOf(0x0a, 0x05, 0x0a, 0x03, 'k'.code.toByte())

        assertThrows(IllegalArgumentException::class.java) {
            MetrolistProviderPreferences.parse(truncated)
        }
    }

    private fun preferenceMap(vararg entries: ByteArray): ByteArray =
        entries.fold(byteArrayOf()) { result, entry -> result + lengthDelimited(1, entry) }

    private fun preferenceEntry(key: String, value: ByteArray): ByteArray =
        lengthDelimited(1, key.toByteArray()) + lengthDelimited(2, value)

    private fun lengthDelimited(fieldNumber: Int, value: ByteArray): ByteArray =
        encodeVarint((fieldNumber shl 3) or 2) + encodeVarint(value.size) + value

    private fun encodeVarint(value: Int): ByteArray {
        var remaining = value
        val result = mutableListOf<Byte>()
        do {
            var current = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            result += current.toByte()
        } while (remaining != 0)
        return result.toByteArray()
    }
}
