package dev.rtsearch.tv.data

import dev.rtsearch.tv.web.FetchResult
import dev.rtsearch.tv.web.WebBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Rutracker(private val web: WebBridge) {

    val isLoggedIn: Boolean get() = web.hasCookie("bb_session")

    /** Categories and forums from the search form; filled on the first search. */
    var categories: List<ForumCategory> = emptyList()
        private set

    /** [forumIds] narrows the search to these forums; empty means the whole tracker. */
    suspend fun search(query: String, sort: SortOrder, forumIds: Collection<String>): SearchPage {
        val forums = forumIds.joinToString("") { "&f%5B%5D=$it" }
        val url = web.base + "tracker.php?nm=" + cp1251(query) + "&o=${sort.param}&s=2" + forums
        return searchUrl(url)
    }

    suspend fun loadCategories(): List<ForumCategory> {
        if (categories.isEmpty()) {
            val doc = load(web.base + "tracker.php")
            if (doc.selectFirst("#logged-in-username") == null) throw NeedLoginException()
            categories = parseCategories(doc)
        }
        return categories
    }

    suspend fun searchUrl(url: String): SearchPage {
        val doc = load(url)
        if (categories.isEmpty()) categories = parseCategories(doc)
        val rows = doc.select("#tor-tbl tbody tr").filter { it.selectFirst("a.tLink") != null }
        if (rows.isEmpty() && doc.selectFirst("#logged-in-username") == null) throw NeedLoginException()
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val items = rows.map { row ->
            val link = row.selectFirst("a.tLink")!!
            val sizeCell = row.selectFirst("td.tor-size")
            val bytes = sizeCell?.attr("data-ts_text")?.toLongOrNull()
            val time = row.select("td").last()?.attr("data-ts_text")?.toLongOrNull()
            TorrentItem(
                id = link.attr("data-topic_id").ifEmpty { topicId(link.attr("href")) },
                title = link.text(),
                forum = row.selectFirst("td.f-name-col a, .f-name a")?.text().orEmpty(),
                size = bytes?.let(::humanSize) ?: sizeCell?.text()?.replace("↓", "")?.trim().orEmpty(),
                seeds = row.selectFirst(".seedmed")?.text()?.trim()?.toIntOrNull() ?: 0,
                leeches = row.selectFirst(".leechmed")?.text()?.trim()?.toIntOrNull() ?: 0,
                downloads = row.selectFirst("td.number-format")?.text().orEmpty(),
                date = time?.let { dateFormat.format(Date(it * 1000)) }.orEmpty(),
            )
        }
        // The counter reads "<label>: 500 (max: 500)"; rely on the language-neutral "(max: N)" suffix.
        val total = Regex("(\\d+)\\s*\\(max:\\s*\\d+\\)").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        // The next page is the pager link whose offset follows the current one.
        val start = startOffset(url)
        val next = doc.select("a.pg").firstOrNull { startOffset(it.attr("href")) == start + PAGE_SIZE }?.absUrl("href")
        return SearchPage(items, total, next?.ifEmpty { null })
    }

    private fun parseCategories(doc: Document): List<ForumCategory> =
        doc.select("#fs-main optgroup").mapNotNull { group ->
            val options = group.select("option")
            val forums = options.map { opt ->
                val id = opt.attr("value")
                val isSub = !opt.hasClass("root_forum")
                val children = if (isSub) emptyList() else options.filter { it.hasClass("fp-$id") }.map { it.attr("value") }
                Forum(
                    id = id,
                    name = opt.text().replace('\u00A0', ' ').trim().removePrefix("|-").trim(),
                    isSub = isSub,
                    ids = listOf(id) + children,
                )
            }
            val name = group.attr("label").replace('\u00A0', ' ').trim()
            if (forums.isEmpty() || name.isEmpty()) null else ForumCategory(name, forums)
        }

    suspend fun topic(id: String): TopicDetails {
        val doc = load(web.base + "viewtopic.php?t=$id")
        val post = doc.selectFirst(".post_body")
        val poster = post?.selectFirst("var.postImg")?.attr("title")?.ifEmpty { null }
            ?: post?.selectFirst("img.postImg")?.absUrl("src")?.ifEmpty { null }
        return TopicDetails(
            id = id,
            title = doc.selectFirst("#topic-title")?.text()
                ?: doc.selectFirst("h1.maintitle")?.text().orEmpty(),
            size = doc.selectFirst("#tor-size-humn")?.text(),
            seeds = doc.selectFirst(".seed b")?.text(),
            leeches = doc.selectFirst(".leech b")?.text(),
            magnet = doc.selectFirst("a.magnet-link")?.attr("href")?.ifEmpty { null },
            hasTorrentFile = doc.selectFirst("a.dl-link") != null,
            posterUrl = poster?.takeIf { it.startsWith("http") },
            description = post?.let(::postText).orEmpty(),
        )
    }

    suspend fun downloadTorrent(id: String): ByteArray {
        val r = web.fetch(web.base + "dl.php?t=$id")
        val bencoded = r.bytes.isNotEmpty() && r.bytes[0] == 'd'.code.toByte()
        if (r.status == 200 && bencoded) return r.bytes
        if (r.url.contains("login.php") || !isLoggedIn) throw NeedLoginException()
        throw IOException("Could not download the .torrent file (HTTP ${r.status})")
    }

    /** Returns null on success, otherwise an error message. */
    suspend fun login(username: String, password: String): String? {
        val body = "login_username=${cp1251(username)}&login_password=${cp1251(password)}&login=$LOGIN_BUTTON_VALUE"
        val r = web.fetch(web.base + "login.php", body)
        if (isLoggedIn) return null
        val doc = parse(r)
        return when {
            doc.selectFirst("input[name^=cap_code], img[src*=captcha]") != null ->
                "The site asks for a captcha, sign in through the website"
            else -> doc.selectFirst(".warnColor1, h4.warnColor1")?.text()?.ifEmpty { null }
                ?: "Sign-in failed (wrong username or password?)"
        }
    }

    suspend fun loginInBrowser(): Boolean =
        web.showBrowser(web.base + "login.php", "Sign in to your account. This window closes by itself afterwards.") { isLoggedIn }

    fun logout() = web.clearCookies()

    private suspend fun load(url: String): Document {
        val r = web.fetch(url)
        if (r.status >= 400) throw IOException("The site returned HTTP ${r.status}")
        if (r.url.contains("login.php")) throw NeedLoginException()
        return parse(r)
    }

    private suspend fun parse(r: FetchResult): Document = withContext(Dispatchers.Default) {
        Jsoup.parse(r.text(), r.url)
    }

    private fun cp1251(s: String) = URLEncoder.encode(s, "windows-1251")

    private fun topicId(href: String) = Regex("t=(\\d+)").find(href)?.groupValues?.get(1).orEmpty()

    private fun startOffset(url: String) = Regex("[?&]start=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun humanSize(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024; i++
        }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", v, units[i])
    }

    /** Text of the first post with line breaks, split into paragraphs. */
    private fun postText(post: Element): List<String> {
        post.select("script, style, .sp-head, .q-head, .post-img").remove()
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    val block = node.tagName() in setOf("br", "div", "p", "li", "hr", "ol", "ul", "h1", "h2", "h3") ||
                        node.hasClass("post-br")
                    if (block) sb.append('\n')
                    if (node.tagName() == "var" && node.hasClass("postImg")) return
                    node.childNodes().forEach(::walk)
                    if (block && node.tagName() != "br") sb.append('\n')
                }
            }
        }
        walk(post)
        val lines = sb.lines().map { it.trim() }
        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty() || current.size >= 6) {
                if (current.isNotEmpty()) paragraphs += current.joinToString("\n")
                current.clear()
            }
            if (line.isNotEmpty()) current += line
        }
        if (current.isNotEmpty()) paragraphs += current.joinToString("\n")
        return paragraphs.take(150)
    }

    private companion object {
        const val PAGE_SIZE = 50

        /** The site's login button value, URL-encoded in windows-1251 as its form expects. */
        const val LOGIN_BUTTON_VALUE = "%E2%F5%EE%E4"
    }
}
