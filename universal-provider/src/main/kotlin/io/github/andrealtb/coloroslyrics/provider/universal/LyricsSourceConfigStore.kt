package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.Context
import org.json.JSONArray

internal object LyricsSourceConfigStore {
    private const val PREFS = "lyrics_sources"
    private const val KEY = "priority"
    /** Every source in its default order; a stored order keeps only the ones the user enabled. */
    val ALL = listOf("QQ", "NETEASE", "KUGOU", "APPLE_MUSIC")
    private val defaults = ALL

    fun read(context: Context): List<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)?.let(::decode) ?: defaults

    fun write(context: Context, values: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(values).toString()).apply()
    }

    fun encode(values: List<String>): String = JSONArray(values).toString()

    fun decode(value: String): List<String> = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).map { array.getString(it) }
            .map {
                when (it) {
                    "PAX_APPLE_MUSIC" -> "APPLE_MUSIC"
                    "BETTER_LYRICS" -> ""
                    else -> it
                }
            }
            .filter { it in defaults }
            .distinct()
    }.getOrDefault(defaults)
}
