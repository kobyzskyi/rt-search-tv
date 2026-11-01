package dev.rtsearch.tv.data

data class TorrentItem(
    val id: String,
    val title: String,
    val forum: String,
    val size: String,
    val seeds: Int,
    val leeches: Int,
    val downloads: String,
    val date: String,
)

data class SearchPage(
    val items: List<TorrentItem>,
    val total: Int?,
    val nextUrl: String?,
)

data class TopicDetails(
    val id: String,
    val title: String,
    val size: String?,
    val seeds: String?,
    val leeches: String?,
    val magnet: String?,
    val hasTorrentFile: Boolean,
    val posterUrl: String?,
    val description: List<String>,
)

/** Sort order for tracker.php: the "o" parameter, always descending (s=2). */
enum class SortOrder(val label: String, val param: Int) {
    SEEDS("Seeds", 10),
    DATE("Date", 1),
    SIZE("Size", 7),
    DOWNLOADS("Downloads", 4),
}

class NeedLoginException : Exception("Please sign in to your account")

data class Forum(val id: String, val name: String, val isSub: Boolean, val ids: List<String>)

data class ForumCategory(val name: String, val forums: List<Forum>) {
    val ids: List<String> get() = forums.map { it.id }
}

/** One selected category or forum: a label for the UI and its forum ids for tracker.php (f[]=...). */
data class CategoryFilter(val label: String, val ids: List<String>)

/** A short description of a multi-selection for buttons. */
fun List<CategoryFilter>.summary(): String = when (size) {
    0 -> "All categories"
    1 -> first().label
    else -> "$size categories"
}

/** Forum ids to search in; overlapping selections are merged. */
fun List<CategoryFilter>.forumIds(): Set<String> = flatMapTo(linkedSetOf()) { it.ids }
