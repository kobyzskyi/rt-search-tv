package dev.rtsearch.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.data.CategoryFilter

/**
 * Tracker categories on the left, forums of the current category on the right.
 * OK on a category searches the whole category; OK on a forum searches that forum and its subforums.
 */
@Composable
fun CategoriesScreen(c: AppController, pickDefault: Boolean) {
    val categories = c.categories
    val selected = if (pickDefault) c.defaultFilter else c.filter
    // 0 is "All categories", followed by the categories in order.
    var current by remember { mutableIntStateOf(0) }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(categories) {
        if (categories.isNotEmpty()) {
            current = categories.indexOfFirst { it.ids == selected?.ids }.let { if (it >= 0) it + 1 else 0 }
            runCatching { initialFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text(if (pickDefault) "Default category" else "Search category", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Current: ${selected?.label ?: "all categories"}", color = Muted)
        Spacer(Modifier.height(12.dp))

        val error = c.categoriesError
        when {
            error != null -> Text(error, color = LeechColor)
            categories.isEmpty() -> LoadingRow("Loading categories...")
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyColumn(
                    modifier = Modifier.width(420.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        ListItem(
                            selected = current == 0,
                            onClick = { c.chooseCategory(null) },
                            headlineContent = { Text("All categories") },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) current = 0 }
                                .then(if (current == 0) Modifier.focusRequester(initialFocus) else Modifier),
                        )
                    }
                    itemsIndexed(categories) { index, category ->
                        val pos = index + 1
                        ListItem(
                            selected = current == pos,
                            onClick = { c.chooseCategory(CategoryFilter(category.name, category.ids)) },
                            headlineContent = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${category.forums.size} forums") },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) current = pos }
                                .then(if (selected?.ids == category.ids) Modifier.focusRequester(initialFocus) else Modifier),
                        )
                    }
                }

                val category = categories.getOrNull(current - 1)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (category == null) {
                        item { Text("Search the whole tracker.", color = Muted) }
                    } else {
                        items(category.forums, key = { it.id }) { forum ->
                            ListItem(
                                selected = selected?.ids == forum.ids,
                                onClick = { c.chooseCategory(CategoryFilter(forum.name, forum.ids)) },
                                headlineContent = {
                                    Text(
                                        forum.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (forum.isSub) FontWeight.Normal else FontWeight.SemiBold,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = if (forum.isSub) 32.dp else 0.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
