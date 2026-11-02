package dev.rtsearch.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import dev.rtsearch.tv.data.CategoryFilter
import dev.rtsearch.tv.data.ForumCategory
import dev.rtsearch.tv.data.NeedLoginException
import dev.rtsearch.tv.data.Rutracker
import dev.rtsearch.tv.data.SortOrder
import dev.rtsearch.tv.data.TopicDetails
import dev.rtsearch.tv.data.TorrentItem
import dev.rtsearch.tv.data.forumIds
import org.json.JSONArray
import org.json.JSONObject
import dev.rtsearch.tv.update.Release
import dev.rtsearch.tv.update.Updater
import dev.rtsearch.tv.web.ChallengeCancelledException
import dev.rtsearch.tv.web.WebBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Search : Screen
    data class Topic(val item: TorrentItem) : Screen
    data object Settings : Screen

    /** [pickDefault] edits the default categories from Settings instead of the current search filter. */
    data class Categories(val pickDefault: Boolean) : Screen
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

val MIRRORS = listOf("rutracker.org", "rutracker.net", "rutracker.nl")

class AppController(
    private val context: Context,
    private val web: WebBridge,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val repo = Rutracker(web)
    private val updater = Updater(context)

    var screen by mutableStateOf<Screen>(Screen.Search)
        private set

    // Search
    var query by mutableStateOf("")
    var sort by mutableStateOf(SortOrder.SEEDS)
        private set
    var results by mutableStateOf<List<TorrentItem>>(emptyList())
        private set
    var total by mutableStateOf<Int?>(null)
        private set
    var nextUrl by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var searchedOnce by mutableStateOf(false)
        private set
    var history by mutableStateOf(loadHistory())
        private set
    /** Index of the last opened release, to restore focus on it. */
    var lastOpenedIndex by mutableStateOf(-1)
        private set
    /** Grows with every new search: the list scrolls to the top and takes focus. */
    var searchToken by mutableStateOf(0)
        private set
    val listState = LazyListState()

    // Category filter: several categories or forums at once; empty means the whole tracker.
    // The search starts with the default selection from Settings.
    var defaultFilter by mutableStateOf(loadFilter())
        private set
    var filter by mutableStateOf(defaultFilter)
        private set
    /** The selection being edited on the categories screen until Apply or Save. */
    var draft by mutableStateOf<List<CategoryFilter>>(emptyList())
        private set
    var categories by mutableStateOf<List<ForumCategory>>(emptyList())
        private set
    var categoriesError by mutableStateOf<String?>(null)
        private set

    // Release
    var topic by mutableStateOf<TopicDetails?>(null)
        private set
    var topicLoading by mutableStateOf(false)
        private set
    var topicError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    // Sign-in
    var host by mutableStateOf(web.host)
        private set
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    /** Whether the tracker session cookie is present; refreshed after signing in or out. */
    var isLoggedIn by mutableStateOf(repo.isLoggedIn)
        private set
    val siteReferer: String get() = web.base

    // Updates
    val currentVersion: String get() = updater.currentVersion
    var autoUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    /** The update banner on the search screen was dismissed with "Later". */
    var updateDismissed by mutableStateOf(false)
        private set
    private var updateJob: Job? = null

    private var returnTo: Screen = Screen.Search
    private var afterLogin: (() -> Unit)? = null
    private var searchJob: Job? = null

    fun search(newQuery: String = query) {
        val q = newQuery.trim()
        if (q.isEmpty()) return
        query = q
        rememberQuery(q)
        searchJob?.cancel()
        searchJob = scope.launch {
            searching = true
            searchError = null
            try {
                val page = repo.search(q, sort, filter.forumIds())
                results = page.items
                total = page.total
                nextUrl = page.nextUrl
                lastOpenedIndex = -1
                searchedOnce = true
                searchToken++
            } catch (e: Exception) {
                searchError = handle(e) { search(q) }
            } finally {
                searching = false
            }
        }
    }

    fun changeSort(order: SortOrder) {
        if (order == sort) return
        sort = order
        if (searchedOnce) search()
    }

    fun openCategories(pickDefault: Boolean = false) {
        draft = if (pickDefault) defaultFilter else filter
        screen = Screen.Categories(pickDefault)
        if (categories.isNotEmpty()) return
        categoriesError = null
        scope.launch {
            try {
                categories = repo.loadCategories()
            } catch (e: Exception) {
                categoriesError = handle(e) { openCategories(pickDefault) }
            }
        }
    }

    fun isInDraft(selection: CategoryFilter): Boolean = draft.any { it.ids == selection.ids }

    fun toggleDraft(selection: CategoryFilter) {
        draft = if (isInDraft(selection)) draft.filterNot { it.ids == selection.ids } else draft + selection
    }

    fun clearDraft() {
        draft = emptyList()
    }

    /** Applies the edited selection: as the default (Settings) or as the current search filter. */
    fun applyDraft() {
        if ((screen as? Screen.Categories)?.pickDefault == true) {
            defaultFilter = draft
            prefs.edit().putString("filters", saveFilter(draft)).apply()
            screen = Screen.Settings
            return
        }
        val changed = filter != draft
        filter = draft
        screen = Screen.Search
        if (changed && searchedOnce) search()
    }

    fun loadMore() {
        val url = nextUrl ?: return
        if (searching) return
        searchJob = scope.launch {
            searching = true
            try {
                val page = repo.searchUrl(url)
                results = results + page.items.filter { n -> results.none { it.id == n.id } }
                nextUrl = page.nextUrl
            } catch (e: Exception) {
                toast(handle(e) { loadMore() })
            } finally {
                searching = false
            }
        }
    }

    fun openTopic(item: TorrentItem, index: Int) {
        lastOpenedIndex = index
        screen = Screen.Topic(item)
        topic = null
        topicError = null
        scope.launch {
            topicLoading = true
            try {
                topic = repo.topic(item.id)
            } catch (e: Exception) {
                topicError = handle(e) { openTopic(item, index) }
            } finally {
                topicLoading = false
            }
        }
    }

    fun openMagnet() {
        val magnet = topic?.magnet ?: return
        startViewer(Intent(Intent.ACTION_VIEW, Uri.parse(magnet)))
    }

    fun downloadTorrent() {
        val t = topic ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                val bytes = repo.downloadTorrent(t.id)
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "torrents").apply { mkdirs() }
                        .resolve("${t.id}.torrent")
                        .apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                startViewer(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/x-bittorrent")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            } catch (e: Exception) {
                toast(handle(e) { downloadTorrent() })
            } finally {
                busy = false
            }
        }
    }

    fun back(): Boolean {
        when (screen) {
            is Screen.Topic -> screen = Screen.Search
            is Screen.Categories -> screen = if ((screen as Screen.Categories).pickDefault) Screen.Settings else Screen.Search
            Screen.Settings -> {
                afterLogin = null
                screen = returnTo
            }
            Screen.Search -> return false
        }
        return true
    }

    fun openSettings() {
        if (screen != Screen.Settings) returnTo = screen
        loginError = null
        screen = Screen.Settings
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            loginError = "Enter your username and password"
            return
        }
        runLogin { repo.login(username.trim(), password) }
    }

    fun loginInBrowser() = runLogin { if (repo.loginInBrowser()) null else "Sign-in cancelled" }

    fun logout() {
        repo.logout()
        isLoggedIn = false
        toast("Signed out")
        screen = Screen.Settings
    }

    fun changeHost(newHost: String) {
        web.host = newHost
        host = newHost
        prefs.edit().putString("host", newHost).apply()
    }

    /**
     * Checks GitHub on every start when automatic updates are on. A TV may kill the app while the user is in
     * system settings, so the offer has to come back on the next start rather than hours later.
     */
    fun checkForUpdatesOnStart() {
        if (autoUpdate) checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        updateJob = scope.launch {
            update = UpdateState.Checking
            update = try {
                val release = updater.latestRelease()
                if (release != null && updater.isNewer(release)) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (silent) UpdateState.Idle else UpdateState.Failed(message(e))
            }
            if (update is UpdateState.Available) updateDismissed = false
        }
    }

    fun changeAutoUpdate(enabled: Boolean) {
        autoUpdate = enabled
        prefs.edit().putBoolean("auto_update", enabled).apply()
    }

    fun dismissUpdate() {
        updateDismissed = true
    }

    fun installUpdate() {
        val release = (update as? UpdateState.Available)?.release ?: return
        updateJob = scope.launch {
            update = UpdateState.Downloading(release, 0f)
            try {
                val apk = updater.download(release) { p -> update = UpdateState.Downloading(release, p) }
                update = UpdateState.Available(release)
                // The system installer asks for the "Install unknown apps" permission itself when it is missing
                // and continues afterwards; some TVs open only a generic app list from our own settings link.
                if (!updater.canInstall()) toast("If Android asks, allow RT Search to install apps")
                updater.install(apk)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update = UpdateState.Failed(message(e))
            }
        }
    }

    private fun runLogin(block: suspend () -> String?) {
        if (loginBusy) return
        scope.launch {
            loginBusy = true
            loginError = null
            try {
                val error = block()
                isLoggedIn = repo.isLoggedIn
                if (error == null) {
                    toast("Signed in")
                    screen = returnTo
                    afterLogin?.invoke()
                    afterLogin = null
                } else {
                    loginError = error
                }
            } catch (e: Exception) {
                loginError = message(e)
            } finally {
                loginBusy = false
            }
        }
    }

    /** Turns an error into a message; if sign-in is required, opens the sign-in screen and runs [retry] afterwards. */
    private fun handle(e: Exception, retry: () -> Unit): String {
        if (e is CancellationException) throw e
        if (e is NeedLoginException) {
            isLoggedIn = false
            afterLogin = retry
            openSettings()
        }
        return message(e)
    }

    private fun message(e: Exception): String = when (e) {
        is ChallengeCancelledException -> "The Cloudflare check was not completed"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun startViewer(intent: Intent) {
        try {
            context.startActivity(
                Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            toast("No torrent app found (for example, TorrServe or LibreTorrent)")
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun loadFilter(): List<CategoryFilter> {
        val json = prefs.getString("filters", null)
        if (json == null) {
            // Version 1.0.0 stored a single default category.
            val label = prefs.getString("filter_label", null) ?: return emptyList()
            val ids = prefs.getString("filter_ids", "").orEmpty().split(',').filter { it.isNotBlank() }
            return if (ids.isEmpty()) emptyList() else listOf(CategoryFilter(label, ids))
        }
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val ids = item.getJSONArray("ids")
                CategoryFilter(item.getString("label"), (0 until ids.length()).map { ids.getString(it) })
            }
        }.getOrDefault(emptyList())
    }

    private fun saveFilter(filters: List<CategoryFilter>): String =
        JSONArray(filters.map { JSONObject().put("label", it.label).put("ids", JSONArray(it.ids)) }).toString()

    private fun loadHistory(): List<String> =
        prefs.getString("history", "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun rememberQuery(q: String) {
        history = (listOf(q) + history.filter { !it.equals(q, ignoreCase = true) }).take(12)
        prefs.edit().putString("history", history.joinToString("\n")).apply()
    }

    companion object {
        fun savedHost(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("host", MIRRORS.first())!!
    }
}
