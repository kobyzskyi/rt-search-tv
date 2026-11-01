package dev.rtsearch.tv

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.rtsearch.tv.ui.App
import dev.rtsearch.tv.web.WebBridge

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        val web = WebBridge(webView, lifecycleScope, AppController.savedHost(this))
        val controller = AppController(applicationContext, web, lifecycleScope)
        setContent { App(controller, web, webView) }
        controller.checkForUpdatesOnStart()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
