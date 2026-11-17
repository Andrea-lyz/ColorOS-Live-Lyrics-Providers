package io.github.andrealtb.coloroslyrics.provider.universal.api

import org.json.JSONArray
import org.json.JSONObject

internal object QqSearchResponseParser {
    fun songArray(root: JSONObject): JSONArray {
        val bodies = listOfNotNull(
            root.optJSONObject("req_0")?.optJSONObject("data")?.optJSONObject("body"),
            root.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body"),
            root.optJSONObject("data")?.optJSONObject("body")
        )
        for (body in bodies) {
            val list = body.optJSONObject("song")?.optJSONArray("list")
            if (list != null && list.length() > 0) return expandVersions(list)
            val items = body.optJSONArray("item_song")
            if (items != null && items.length() > 0) return expandVersions(items)
        }
        return JSONArray()
    }

    /**
     * Rows of the web search (`c.y.qq.com/soso/fcgi-bin/search_for_qq_cp`), rewritten to the
     * musicu row shape so scoring and lyric lookup read one format.
     */
    fun webSongArray(root: JSONObject): JSONArray {
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return JSONArray()
        val rows = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            rows.put(JSONObject().apply {
                put("id", item.optLong("songid"))
                put("mid", item.optString("songmid"))
                put("title", item.optString("songname"))
                put("singer", item.optJSONArray("singer") ?: JSONArray())
                put("album", JSONObject().apply {
                    put("name", item.optString("albumname"))
                    put("mid", item.optString("albummid"))
                })
                put("interval", item.optLong("interval"))
            })
        }
        return rows
    }

    /**
     * Other versions of a song (live, remaster, another album) sit under `grp` of the first row.
     * They are listed after it so scoring can pick the edition that matches the player.
     */
    fun expandVersions(rows: JSONArray): JSONArray {
        val expanded = JSONArray()
        val seen = HashSet<String>()
        fun add(row: JSONObject) {
            val key = mid(row).ifBlank { songId(row).toString() }
            if (key.isNotBlank() && key != "0" && seen.add(key)) expanded.put(row)
        }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            add(row)
            val group = row.optJSONArray("grp") ?: continue
            for (j in 0 until group.length()) group.optJSONObject(j)?.let(::add)
        }
        return expanded
    }

    fun title(item: JSONObject): String =
        item.optString("title").ifBlank { item.optString("name") }

    fun mid(item: JSONObject): String =
        item.optString("mid").ifBlank { item.optString("songmid") }

    fun songId(item: JSONObject): Long {
        val id = item.optLong("id")
        return if (id != 0L) id else item.optLong("songid")
    }

    /**
     * Every credited singer, joined the way players render duets. Keeping only the first name made
     * a track credited "Sugarland/Taylor Swift" score as a different artist.
     */
    fun artist(item: JSONObject): String {
        val singers = item.optJSONArray("singer") ?: return ""
        return buildList {
            for (index in 0 until singers.length()) {
                val name = singers.optJSONObject(index)?.optString("name").orEmpty().trim()
                if (name.isNotEmpty()) add(name)
            }
        }.joinToString("/")
    }

    fun album(item: JSONObject): String {
        val album = item.optJSONObject("album") ?: return ""
        return album.optString("name").ifBlank { album.optString("title") }
    }

    fun durationMs(item: JSONObject): Long {
        val intervalSec = item.optLong("interval")
        if (intervalSec > 0L) return intervalSec * 1000L
        val intervalMs = item.optLong("interval_ms")
        return if (intervalMs > 0L) intervalMs else 0L
    }

    fun coverUrl(item: JSONObject): String {
        val album = item.optJSONObject("album") ?: return ""
        val pmid = album.optString("pmid").ifBlank { album.optString("mid") }
        if (pmid.isBlank()) return ""
        return "https://y.gtimg.cn/music/photo_new/T002R300x300M000$pmid.jpg"
    }
}
