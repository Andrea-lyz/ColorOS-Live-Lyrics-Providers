package io.github.proify.lyricon.kwprovider.xposed

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

internal object KuWoLyriconSongNormalizer {
    fun normalize(song: Song): Song {
        val output = song.deepCopy()
        val lines = output.lyrics.orEmpty()
        lines.forEachIndexed { index, line ->
            normalizeLine(output, lines, index, line)
        }
        return output
    }

    private fun normalizeLine(
        song: Song,
        lines: List<RichLyricLine>,
        index: Int,
        line: RichLyricLine
    ) {
        val begin = line.begin
        var end = line.end
        if (end <= begin) {
            val nextBegin = lines.getOrNull(index + 1)?.begin?.takeIf { it > begin }
            val wordEnd = sequenceOf(
                line.words.orEmpty(),
                line.secondaryWords.orEmpty(),
                line.translationWords.orEmpty()
            ).flatten().maxOfOrNull { it.end }
            end = when {
                wordEnd != null && wordEnd > begin -> {
                    if (nextBegin == null) wordEnd else wordEnd.coerceAtMost(nextBegin)
                }
                nextBegin != null -> nextBegin
                song.duration > begin -> song.duration
                else -> begin + 1L
            }
        }
        if (end > begin) {
            line.end = end
            line.duration = end - begin
        }
    }
}
