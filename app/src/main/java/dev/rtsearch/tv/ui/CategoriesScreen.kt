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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.data.CategoryFilter
import dev.rtsearch.tv.data.summary

/**
 * Tracker categories on the left, forums of the current category on the right.
 * OK toggles a whole category or a single forum (with its subforums); several can be selected.
 * Apply (or Save for the default selection) confirms; Back leaves without changes.
 */
@Composable
fun CategoriesScreen(c: AppController, pickDefault: Boolean) {
    val categories = c.categories
    // Index of the category whose forums are shown on the right.
    var current by remember { mutableIntStateOf(0) }
    val applyFocus = remember { FocusRequester() }

    LaunchedEffect(categories) {
        if (categories.isNotEmpty()) {
            val firstSelected = categories.indexOfFirst { cat -> cat.forums.any { f -> c.draft.any { f.id in it.ids } } }
            current = firstSelected.coerceAtLeast(0)
            runCatching { applyFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (pickDefault) "Default categories" else "Search categories", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Selected: ${c.draft.summary()}", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { c.applyDraft() }, modifier = Modifier.focusRequester(applyFocus)) {
                Text(if (pickDefault) "Save" else "Apply")
            }
            OutlinedButton(onClick = { c.clearDraft() }) { Text("Clear") }
        }
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
                    itemsIndexed(categories) { index, category ->
                        val selection = CategoryFilter(category.name, category.ids)
                        val selectedForums = category.forums.count { f -> c.draft.any { f.id in it.ids } }
                        ListItem(
                            selected = c.isInDraft(selection),
                            onClick = { c.toggleDraft(selection) },
                            headlineContent = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    when {
                                        c.isInDraft(selection) -> "Whole category selected"
                                        selectedForums > 0 -> "$selectedForums of ${category.forums.size} forums selected"
                                        else -> "${category.forums.size} forums"
                                    },
                                )
                            },
                            modifier = Modifier.onFocusChanged { if (it.isFocused) current = index },
                        )
                    }
                }

                val category = categories.getOrNull(current)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (category != null) {
                        items(category.forums, key = { it.id }) { forum ->
                            val selection = CategoryFilter(forum.name, forum.ids)
                            ListItem(
                                selected = c.isInDraft(selection),
                                onClick = { c.toggleDraft(selection) },
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
