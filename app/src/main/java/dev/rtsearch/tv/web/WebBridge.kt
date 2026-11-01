package dev.rtsearch.tv.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class FetchResult(
    val status: Int,
    val url: String,
    val contentType: String,
    val cfMitigated: String,
    val bytes: ByteArray,
) {
    val isChallenge: Boolean
        get() = cfMitigated.equals("challenge", ignoreCase = true) ||
            (status == 403 && String(bytes, Charsets.ISO_8859_1).let { "challenge-platform" in it || "_cf_chl_opt" in it })

    fun text(): String {
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)
            ?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charset.forName("windows-1251")
        return String(bytes, cs)
    }
}

class ChallengeCancelledException : Exception("Verification was not completed")

/** What the visible browser shows and when it closes by itself. */
class BrowserSession(
    val hint: String,
    val finishWhen: suspend () -> Boolean,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/**
 * All tracker requests go through a single WebView, so they share the cookies (cf_clearance, bb_session),
 * the User-Agent and the TLS fingerprint of the page where the user passed the Cloudflare check.
 * Requests are made with fetch() inside a tracker page, without rendering anything.
 *
 * If Cloudflare still asks for a check, the WebView is shown to the user ([session]) to complete it;
 * usually it passes automatically within a few seconds.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebBridge(
    private val webView: WebView,
    private val scope: CoroutineScope,
    host: String,
) {

    var host: String = host
        set(value) {
            field = value
            originReady = false
        }
    val base: String get() = "https://$host/forum/"

    /** The visible browser, when the user's help is needed. */
    var session by mutableStateOf<BrowserSession?>(null)
        private set

    private data class PageState(val url: String?, val loading: Boolean)

    private val page = MutableStateFlow(PageState(null, false))
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<FetchResult>>()
    private val ids = AtomicInteger()
    private var originReady = false
    private val originLock = Mutex()

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.addJavascriptInterface(JsCallback(), "RtBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                page.value = PageState(url, true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                page.value = PageState(url, false)
                if (session != null) {
                    scope.launch {
                        delay(500)
                        if (!page.value.loading) onBrowserPageLoaded()
                    }
                }
            }
        }
    }

    fun hasCookie(name: String): Boolean =
        CookieManager.getInstance().getCookie(base)?.split(';')
            ?.any { it.trim().startsWith("$name=") } == true

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        originReady = false
    }

    /** GET or POST (application/x-www-form-urlencoded) request made by the tracker page. */
    suspend fun fetch(url: String, formBody: String? = null): FetchResult {
        ensureOrigin()
        var result = jsFetch(url, formBody)
        if (result.isChallenge) {
            // Cloudflare protects specific pages (search, topics) rather than the home page,
            // so open the blocked page itself in the WebView and let the check run there.
            originReady = false
            ensureOrigin(url)
            result = jsFetch(url, formBody)
        }
        if (webView.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug aid for parsing: the last site response (adb shell run-as <package> cat cache/last-response.html).
            runCatching { java.io.File(webView.context.cacheDir, "last-response.html").writeBytes(result.bytes) }
        }
        return result
    }

    /** Shows the site to the user (for example, to sign in with a captcha) and waits for the condition. */
    suspend fun showBrowser(url: String, hint: String, finishWhen: suspend () -> Boolean): Boolean =
        withContext(Dispatchers.Main) {
            webView.loadUrl(url)
            val s = BrowserSession(hint, finishWhen)
            session = s
            webView.requestFocus()
            try {
                s.result.await()
            } finally {
                session = null
                originReady = false
            }
        }

    /** Called from the UI: "Done" (true) or "Cancel" (false). */
    fun finishBrowser(ok: Boolean) {
        session?.result?.complete(ok)
    }

    /** Checks [BrowserSession.finishWhen] after every loaded page. */
    suspend fun onBrowserPageLoaded() {
        val s = session ?: return
        if (!isChallengePage() && s.finishWhen()) s.result.complete(true)
    }

    private suspend fun ensureOrigin(pageUrl: String = base + "index.php") =
        originLock.withLock { withContext(Dispatchers.Main) { prepareOrigin(pageUrl) } }

    private suspend fun prepareOrigin(pageUrl: String) {
        if (originReady && Uri.parse(webView.url ?: "").host == host) return
        loadAndWait(pageUrl)
        if (isChallengePage()) {
            // The non-interactive check usually passes by itself and reloads the page.
            withTimeoutOrNull(12_000) {
                while (true) {
                    page.first { !it.loading }
                    delay(700)
                    if (!page.value.loading && !isChallengePage()) break
                }
            }
        }
        if (isChallengePage()) {
            val passed = showBrowser(
                pageUrl,
                "Complete the Cloudflare check. This window closes by itself.",
            ) { Uri.parse(webView.url ?: "").host == host }
            if (!passed) throw ChallengeCancelledException()
            if (Uri.parse(webView.url ?: "").host != host) loadAndWait(base + "index.php")
        }
        originReady = true
    }

    private suspend fun loadAndWait(url: String) {
        page.value = PageState(url, true)
        webView.loadUrl(url)
        withTimeoutOrNull(30_000) { page.first { !it.loading } }
    }

    private suspend fun isChallengePage(): Boolean = withContext(Dispatchers.Main) {
        evalJs(
            "!!(window._cf_chl_opt || document.querySelector(" +
                "'#challenge-form,#challenge-stage,#challenge-error-text,script[src*=\"challenge-platform\"]'))",
        ) == "true"
    }

    private suspend fun evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { if (cont.isActive) cont.resume(it) }
    }

    private suspend fun jsFetch(url: String, formBody: String?): FetchResult {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<FetchResult>()
        pending[id] = deferred
        val init = if (formBody == null) {
            "{credentials:'include'}"
        } else {
            "{method:'POST',credentials:'include'," +
                "headers:{'Content-Type':'application/x-www-form-urlencoded'}," +
                "body:${JSONObject.quote(formBody)}}"
        }
        val script = """
            (async () => {
              try {
                const r = await fetch(${JSONObject.quote(url)}, $init);
                const u = new Uint8Array(await r.arrayBuffer());
                let bin = '';
                for (let i = 0; i < u.length; i += 0x8000) {
                  bin += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
                }
                RtBridge.onResult($id, r.status, r.url, r.headers.get('content-type') || '',
                  r.headers.get('cf-mitigated') || '', btoa(bin));
              } catch (e) {
                RtBridge.onError($id, String(e));
              }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) { webView.evaluateJavascript(script, null) }
        return try {
            withTimeoutOrNull(60_000) { deferred.await() }
                ?: throw java.io.IOException("The site did not respond in time")
        } finally {
            pending.remove(id)
        }
    }

    private inner class JsCallback {
        @JavascriptInterface
        fun onResult(id: Int, status: Int, url: String, contentType: String, cf: String, b64: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            pending[id]?.complete(FetchResult(status, url, contentType, cf, bytes))
        }

        @JavascriptInterface
        fun onError(id: Int, message: String) {
            pending[id]?.completeExceptionally(java.io.IOException("Network error: $message"))
        }
    }
}
