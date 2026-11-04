package dev.rtsearch.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.LibraryTab
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Favorites and recently opened releases. OK opens a release; holding OK removes it from the current list.
 */
@Composable
fun LibraryScreen(c: AppController) {
    val tab = c.libraryTab
    val entries = if (tab == LibraryTab.FAVORITES) c.favorites else c.viewed
    val tabFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    // The row that should hold focus: the release we came back from, or the neighbour of a removed one.
    var focusIndex by remember(tab) { mutableIntStateOf(c.libraryIndex) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val listState = remember(tab) { LazyListState(c.libraryIndex.coerceAtLeast(0)) }
    val timeFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    LaunchedEffect(tab, focusRequest) {
        val restored = focusIndex in entries.indices && runCatching { rowFocus.requestFocus() }.isSuccess
        if (!restored) runCatching { tabFocus.requestFocus() }
    }

    fun remove(index: Int) {
        val item = entries[index].item
        if (tab == LibraryTab.FAVORITES) c.toggleFavorite(item) else c.removeViewed(item.id)
        focusIndex = minOf(index, entries.size - 2)
        focusRequest++
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            LibraryTab.entries.forEach { t ->
                val count = if (t == LibraryTab.FAVORITES) c.favorites.size else c.viewed.size
                val label = "${t.label} ($count)"
                if (t == tab) {
                    Button(onClick = {}, modifier = Modifier.focusRequester(tabFocus)) { Text(label) }
                } else {
                    OutlinedButton(onClick = { c.changeLibraryTab(t) }) { Text(label) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (tab == LibraryTab.HISTORY && entries.isNotEmpty()) {
                OutlinedButton(onClick = {
                    c.clearViewed()
                    focusIndex = -1
                    focusRequest++
                }) { Text("Clear history") }
            }
        }
        Text(
            when {
                entries.isEmpty() && tab == LibraryTab.FAVORITES ->
                    "No favorites yet. Open a release and press \"Add to favorites\"."
                entries.isEmpty() -> "Releases you open appear here."
                else -> "Hold OK on a release to remove it from this list."
            },
            color = Muted,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(entries, key = { _, it -> it.item.id }) { index, entry ->
                val item = entry.item
                val verb = if (tab == LibraryTab.FAVORITES) "added" else "opened"
                ResultRow(
                    item = item,
                    favorite = tab == LibraryTab.HISTORY && c.isFavorite(item.id),
                    modifier = if (index == focusIndex) Modifier.focusRequester(rowFocus) else Modifier,
                    details = (listOf(item.forum, item.size) + "$verb ${timeFormat.format(Date(entry.time))}")
                        .filter { it.isNotEmpty() }
                        .joinToString("  •  "),
                    onLongClick = { remove(index) },
                    onClick = { c.openSaved(item, index) },
                )
            }
        }
    }
}
