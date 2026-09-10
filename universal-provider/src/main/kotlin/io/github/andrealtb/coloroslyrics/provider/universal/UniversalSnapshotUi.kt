package io.github.andrealtb.coloroslyrics.provider.universal

/** Keep diagnostic-only updates (for example Bluetooth rawTitle) out of UI state. */
internal object UniversalSnapshotUi {
    private val fields = setOf(
        "title", "artist", "album", "package", "generation",
        "lyricSource", "lyricStatus", "cachedSongs"
    )

    fun parse(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator >= 0) {
                val key = line.substring(0, separator).trim()
                if (key in fields) put(key, line.substring(separator + 1).trim())
            }
        }
    }
}
