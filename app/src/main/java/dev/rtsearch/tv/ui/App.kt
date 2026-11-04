package dev.rtsearch.tv.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.Screen
import dev.rtsearch.tv.web.WebBridge

val Background = Color(0xFF101722)

@Composable
fun App(controller: AppController, web: WebBridge, webView: WebView) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7FC1FF),
            onPrimary = Color(0xFF00213D),
            surface = Color(0xFF1B2433),
            background = Background,
        ),
    ) {
        // Without a Surface the default text color is black; use a light one on the dark background.
        CompositionLocalProvider(LocalContentColor provides Color(0xFFE6ECF5)) {
        Box(Modifier.fillMaxSize().background(Background)) {
            // The WebView always stays in the hierarchy (under the UI) so Cloudflare scripts run as in a browser.
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

            val session = web.session
            SideEffect {
                // The hidden WebView must not take remote control focus.
                webView.isFocusable = session != null
                webView.isFocusableInTouchMode = session != null
                if (session != null) webView.requestFocus()
            }
            if (session != null) {
                BackHandler { web.finishBrowser(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0xE6101722))
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(session.hint, Modifier.weight(1f), color = Color.White)
                    Button(onClick = { web.finishBrowser(true) }) { Text("Done") }
                    OutlinedButton(onClick = { web.finishBrowser(false) }) { Text("Cancel") }
                }
            } else {
                Column(Modifier.fillMaxSize().background(Background)) {
                    BackHandler(enabled = controller.screen != Screen.Search) { controller.back() }
                    when (val screen = controller.screen) {
                        Screen.Search -> SearchScreen(controller)
                        is Screen.Topic -> TopicScreen(controller, screen.item)
                        Screen.Settings -> SettingsScreen(controller)
                        Screen.Library -> LibraryScreen(controller)
                        is Screen.Categories -> CategoriesScreen(controller, screen.pickDefault)
                    }
                }
            }
        }
        }
    }
}
