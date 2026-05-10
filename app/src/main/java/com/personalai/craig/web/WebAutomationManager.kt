package com.personalai.craig.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Controls a hidden WebView to automate the OpticSEO website.
 * All WebView operations must run on the main thread.
 */
@Singleton
class WebAutomationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebAutomationManager"
        private const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val NAV_TIMEOUT_MS = 15_000L
    }

    @get:SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView by lazy {
        // Must be created and configured on the main thread; callers use withContext(Main).
        WebView(context).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "Mozilla/5.0 (Linux; Android 14; RedMagic 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            // Phone-sized viewport so SPA layout calculations work
            val w = 1080
            val h = 2340
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
            // Resume JS timers — headless WebViews start paused on some builds
            wv.resumeTimers()
            @Suppress("DEPRECATION") wv.onResume()
            // Default WebViewClient that bypasses bot-detection on every page load
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    // Unset navigator.webdriver so the site doesn't detect automation
                    view.evaluateJavascript(
                        "(function(){try{Object.defineProperty(navigator,'webdriver',{get:()=>false})}catch(e){}})();",
                        null
                    )
                }
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.proceed()
                }
            }
        }
    }

    /**
     * Navigate to a URL and wait for the page to finish loading.
     * Uses a temporary WebViewClient that restores the default one after navigation.
     */
    suspend fun navigate(url: String): String = withContext(Dispatchers.Main) {
        withTimeoutOrNull(NAV_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        view.evaluateJavascript(
                            "(function(){try{Object.defineProperty(navigator,'webdriver',{get:()=>false})}catch(e){}})();",
                            null
                        )
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (cont.isActive) cont.resume("Navigated to $url")
                    }
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.proceed()
                    }
                }
                cont.invokeOnCancellation { webView.stopLoading() }
                webView.loadUrl(url)
            }
        } ?: "Navigation timed out for $url"
    }

    /**
     * Click an element by its visible text content.
     */
    suspend fun clickByText(text: String): String = evaluateJs(
        """
        (function() {
            var selectors = ['button', 'a', '[role="button"]', 'input[type="submit"]', 'li', 'span', 'div'];
            for (var s = 0; s < selectors.length; s++) {
                var els = document.querySelectorAll(selectors[s]);
                for (var i = 0; i < els.length; i++) {
                    var elText = (els[i].innerText || els[i].textContent || els[i].value || '').trim();
                    if (elText.toLowerCase().includes('${text.lowercase().replace("'", "\\'")}')) {
                        els[i].click();
                        return 'clicked: ' + elText.substring(0, 60);
                    }
                }
            }
            return 'element not found with text: ${text.replace("'", "\\'")}';
        })()
        """.trimIndent()
    )

    /**
     * Click an element by CSS selector.
     */
    suspend fun clickBySelector(selector: String): String = evaluateJs(
        """
        (function() {
            var el = document.querySelector('${selector.replace("'", "\\'")}');
            if (el) { el.click(); return 'clicked: ' + el.tagName + ' ' + (el.innerText||'').substring(0,40); }
            return 'selector not found: ${selector.replace("'", "\\'")}';
        })()
        """.trimIndent()
    )

    /**
     * Fill an input field with a value.
     * Uses the native HTMLInputElement.prototype.value setter so React/Vue synthetic
     * event handlers fire correctly (plain el.value= assignment bypasses them).
     */
    suspend fun fillInput(selector: String, value: String): String = evaluateJs(
        """
        (function() {
            var el = document.querySelector('${selector.replace("'", "\\'")}');
            if (!el) return 'input not found: ${selector.replace("'", "\\'")}';
            el.scrollIntoView();
            el.focus();
            try {
                var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                nativeSetter.call(el, '${value.replace("'", "\\'")}');
            } catch(e) {
                el.value = '${value.replace("'", "\\'")}';
            }
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true}));
            return 'filled input with value';
        })()
        """.trimIndent()
    )

    /**
     * Read the current page's URL and visible text content.
     */
    suspend fun readPageContent(): String {
        val text = evaluateJs(
            """
            (function() {
                var body = document.body ? document.body.innerText : '';
                return 'URL: ' + window.location.href + '\n\nPage content:\n' + body.substring(0, 10000);
            })()
            """.trimIndent()
        )
        return text.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
    }

    /**
     * Submit a form identified by CSS selector.
     */
    suspend fun submitForm(selector: String): String = evaluateJs(
        """
        (function() {
            var form = document.querySelector('${selector.replace("'", "\\'")}');
            if (!form) return 'form not found: ${selector.replace("'", "\\'")}';
            form.submit();
            return 'form submitted';
        })()
        """.trimIndent()
    )

    /**
     * Wait for a CSS selector to appear on the page.
     */
    suspend fun waitForElement(selector: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                var found = false
                while (!found) {
                    val result = evaluateJsOnMain(
                        "document.querySelector('${selector.replace("'", "\\'")}') !== null"
                    )
                    found = result.contains("true")
                    if (!found) kotlinx.coroutines.delay(300)
                }
                "element found: $selector"
            } ?: "element not found within timeout: $selector"
        }

    /**
     * Evaluate arbitrary JavaScript and return the result string.
     */
    suspend fun evaluateJs(script: String): String = withContext(Dispatchers.Main) {
        evaluateJsOnMain(script)
    }

    private suspend fun evaluateJsOnMain(script: String): String =
        withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result ?: "null")
                }
            }
        } ?: "timeout"

    /**
     * Captures the current WebView as a JPEG and returns it as a base64 string.
     * Returns null if capture fails for any reason.
     */
    suspend fun captureScreenshot(): String? = withContext(Dispatchers.Main) {
        try {
            val w = webView.width.coerceAtLeast(100)
            val h = webView.height.coerceAtLeast(100)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}")
            null
        }
    }

    suspend fun getCurrentUrl(): String = withContext(Dispatchers.Main) { webView.url ?: "" }

    suspend fun clearSession() = withContext(Dispatchers.Main) {
        webView.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
