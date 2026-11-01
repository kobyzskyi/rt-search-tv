package dev.rtsearch.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import android.webkit.WebSettings
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.data.TorrentItem

@Composable
fun TopicScreen(c: AppController, item: TorrentItem) {
    val actionFocus = remember { FocusRequester() }
    val topic = c.topic

    LaunchedEffect(topic) {
        if (topic != null) runCatching { actionFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        // Reserve the poster area up front so the text does not shift when the image arrives.
        Poster(c, if (topic == null) null else topic.posterUrl ?: "", Modifier.padding(end = 32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                topic?.title?.ifEmpty { null } ?: item.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(topic?.size ?: item.size, color = Muted)
                Text("↑${topic?.seeds ?: item.seeds}", color = SeedColor, fontWeight = FontWeight.Bold)
                Text("↓${topic?.leeches ?: item.leeches}", color = LeechColor)
                Text(item.forum, color = Muted, maxLines = 1)
            }

            val error = c.topicError
            when {
                c.topicLoading -> LoadingRow("Loading the release...", Modifier.padding(top = 16.dp))
                error != null -> Text(error, color = LeechColor)
                topic != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val first = Modifier.focusRequester(actionFocus)
                        if (topic.magnet != null) {
                            Button(onClick = { c.openMagnet() }, modifier = first) { Text("Open magnet") }
                        }
                        if (topic.hasTorrentFile) {
                            OutlinedButton(
                                onClick = { c.downloadTorrent() },
                                modifier = if (topic.magnet == null) first else Modifier,
                            ) { Text(if (c.busy) "Downloading..." else "Download .torrent") }
                        }
                        OutlinedButton(
                            onClick = { c.back() },
                            modifier = if (topic.magnet == null && !topic.hasTorrentFile) first else Modifier,
                        ) { Text("Back") }
                    }
                    Description(topic.description)
                }
            }
        }
    }
}

/** The description split into paragraphs: each one is focusable, so the remote's arrows scroll the list. */
@Composable
private fun Description(paragraphs: List<String>) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(paragraphs) { p ->
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            Text(
                p,
                fontSize = 16.sp,
                color = if (focused) Color.White else Color(0xFFD0D8E4),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused) Color(0xFF243048) else Color.Transparent)
                    .border(
                        width = if (focused) 1.dp else 0.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .focusable(interactionSource = source)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** url: null while the release is loading, "" when there is no poster. */
@Composable
private fun Poster(c: AppController, url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(
        modifier
            .width(260.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B2433)),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                // Some image hosts serve a placeholder without the tracker's Referer and a browser User-Agent.
                model = remember(url) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .addHeader("Referer", c.siteReferer)
                        .addHeader("User-Agent", WebSettings.getDefaultUserAgent(context))
                        .crossfade(true)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onState = { state = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            url == null || state is AsyncImagePainter.State.Loading -> Spinner(40.dp)
            url.isEmpty() || state is AsyncImagePainter.State.Error -> Text("No poster", color = Muted)
        }
    }
}
