package dev.rtsearch.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.UpdateState
import dev.rtsearch.tv.data.SortOrder
import dev.rtsearch.tv.data.summary
import dev.rtsearch.tv.data.TorrentItem

val Muted = Color(0xFF9AA7BB)
val SeedColor = Color(0xFF6BD48A)
val LeechColor = Color(0xFFE88A7A)

@Composable
fun SearchScreen(c: AppController) {
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    var text by remember { mutableStateOf(c.query) }

    fun submit(q: String = text) {
        text = q
        keyboard?.hide()
        c.search(q)
    }

    LaunchedEffect(Unit) {
        // Coming back from a release: focus it; otherwise focus the search field.
        if (c.lastOpenedIndex >= 0 && c.lastOpenedIndex < c.results.size) {
            runCatching { restoreFocus.requestFocus() }
        } else if (c.results.isEmpty()) {
            runCatching { fieldFocus.requestFocus() }
        }
    }
    val initialToken = remember { c.searchToken }
    LaunchedEffect(c.searchToken) {
        if (c.searchToken != initialToken && c.results.isNotEmpty()) {
            c.listState.scrollToItem(0)
            runCatching { firstItemFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Movie, TV show, game...",
                imeAction = ImeAction.Search,
                onAction = { submit() },
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
            )
            Button(onClick = { submit() }) { Text("Search") }
            OutlinedButton(onClick = { c.openSettings() }) { Text(if (c.isLoggedIn) "Settings" else "Sign in") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sort by:", color = Muted)
            SortOrder.entries.forEach { order ->
                if (order == c.sort) {
                    Button(onClick = {}) { Text(order.label) }
                } else {
                    OutlinedButton(onClick = { c.changeSort(order) }) { Text(order.label) }
                }
            }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { c.openCategories() }) {
                Text(
                    when (c.filter.size) {
                        0 -> "Category: all"
                        1 -> "Category: " + c.filter.summary()
                        else -> "Categories: " + c.filter.size
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                c.searching -> Spinner(28.dp)
                c.searchedOnce -> {
                    val total = c.total ?: c.results.size
                    Text(
                        if (total != c.results.size) "${c.results.size} of $total" else "Found: $total",
                        color = Muted,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        UpdateBanner(c)

        val error = c.searchError
        when {
            error != null && c.results.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, color = LeechColor, fontSize = 18.sp)
                Button(onClick = { c.search() }) { Text("Retry") }
            }
            c.searching && c.results.isEmpty() -> LoadingRow("Searching...", Modifier.padding(top = 8.dp))
            c.results.isEmpty() && c.searchedOnce ->
                Text("Nothing found", color = Muted, fontSize = 18.sp)
            c.results.isEmpty() -> History(c.history) { submit(it) }
            else -> LazyColumn(
                state = c.listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(c.results, key = { _, it -> it.id }) { index, item ->
                    val mod = when (index) {
                        c.lastOpenedIndex -> Modifier.focusRequester(restoreFocus)
                        0 -> Modifier.focusRequester(firstItemFocus)
                        else -> Modifier
                    }
                    ResultRow(item, mod) { c.openTopic(item, index) }
                }
                if (c.nextUrl != null) {
                    item {
                        Button(onClick = { c.loadMore() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (c.searching) "Loading..." else "Show more")
                        }
                    }
                }
            }
        }
    }
}

/** Offers a newer GitHub release until the user installs it or presses "Later". */
@Composable
private fun UpdateBanner(c: AppController) {
    val available = c.update as? UpdateState.Available
    val downloading = c.update as? UpdateState.Downloading
    if ((available == null || c.updateDismissed) && downloading == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(Color(0xFF1B2A41), RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (downloading != null) {
            LoadingRow("Downloading version ${downloading.release.version}: ${(downloading.progress * 100).toInt()}%")
        } else if (available != null) {
            Text("Version ${available.release.version} is available", modifier = Modifier.weight(1f))
            Button(onClick = { c.installUpdate() }) { Text("Install") }
            OutlinedButton(onClick = { c.dismissUpdate() }) { Text("Later") }
        }
    }
}

@Composable
private fun ResultRow(item: TorrentItem, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        headlineContent = {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
        },
        supportingContent = {
            Text(
                listOf(item.forum, item.size, item.date).filter { it.isNotEmpty() }.joinToString("  •  "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("↑${item.seeds}", color = SeedColor, fontWeight = FontWeight.Bold)
                Text("↓${item.leeches}", color = LeechColor)
            }
        },
    )
}

@Composable
private fun History(history: List<String>, onPick: (String) -> Unit) {
    if (history.isEmpty()) {
        Text(
            "Type a query and press Search. Searching requires a tracker account.",
            color = Muted,
            fontSize = 18.sp,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recent searches", color = Muted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { q -> OutlinedButton(onClick = { onPick(q) }) { Text(q) } }
        }
    }
}
