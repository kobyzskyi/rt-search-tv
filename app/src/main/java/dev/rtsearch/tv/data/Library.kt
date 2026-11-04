package dev.rtsearch.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A release kept in the history or favorites, with the time it was opened or added. */
data class SavedTorrent(val item: TorrentItem, val time: Long)

/** Recently opened releases and favorites, stored as JSON in their own preferences file. */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun loadHistory(): List<SavedTorrent> = load(KEY_HISTORY)
    fun loadFavorites(): List<SavedTorrent> = load(KEY_FAVORITES)
    fun saveHistory(list: List<SavedTorrent>) = save(KEY_HISTORY, list)
    fun saveFavorites(list: List<SavedTorrent>) = save(KEY_FAVORITES, list)

    private fun load(key: String): List<SavedTorrent> = runCatching {
        val array = JSONArray(prefs.getString(key, null) ?: return emptyList())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedTorrent(
                TorrentItem(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    forum = o.optString("forum"),
                    size = o.optString("size"),
                    seeds = o.optInt("seeds"),
                    leeches = o.optInt("leeches"),
                    downloads = o.optString("downloads"),
                    date = o.optString("date"),
                ),
                o.optLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<SavedTorrent>) {
        val array = JSONArray(
            list.map { (item, time) ->
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("forum", item.forum)
                    .put("size", item.size)
                    .put("seeds", item.seeds)
                    .put("leeches", item.leeches)
                    .put("downloads", item.downloads)
                    .put("date", item.date)
                    .put("time", time)
            },
        )
        prefs.edit().putString(key, array.toString()).apply()
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val KEY_FAVORITES = "favorites"
    }
}
