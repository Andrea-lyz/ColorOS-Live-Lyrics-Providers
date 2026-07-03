@file:Suppress("PropertyName")

package io.github.proify.lyricon.qishuiprovider.xposed.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class NetResponseCache(
    val lyric: Lyric? = null,
) {

    @Serializable
    class Lyric(
        val type: String? = null,
        val content: String? = null,
        @SerialName("lyric")
        val lyricText: String? = null,
        val lang_translations: Map<String, Translation>? = null
    ) {
        val resolvedContent: String?
            get() = content ?: lyricText
    }

    @Serializable
    class Translation(
        val content: String? = null,
        @SerialName("lyric")
        val lyricText: String? = null,
        val type: String? = null
    ) {
        val resolvedContent: String?
            get() = content ?: lyricText
    }
}
