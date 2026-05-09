package com.personalai.craig.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView by lazy {
        WebView(context).also { wv ->
            mainHandler.post {
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }
        }
    }

    /**
     * Navigate to a URL and wait for the page to finish loading.
     */
    suspend fun navigate(url: String): String = withContext(Dispatchers.Main) {
        withTimeoutOrNull(NAV_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (cont.isActive) cont.resume("Navigated to $url")
                    }
                }
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
     */
    suspend fun fillInput(selector: String, value: String): String = evaluateJs(
        """
        (function() {
            var el = document.querySelector('${selector.replace("'", "\\'")}');
            if (!el) return 'input not found: ${selector.replace("'", "\\'")}';
            el.focus();
            el.value = '${value.replace("'", "\\'")}';
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return 'filled input with value';
        })()
        """.trimIndent()
    )

    /**
     * Read the current page's URL and visible text content.
     */
    suspend fun readPageContent(): String {
        val url = withContext(Dispatchers.Main) { webView.url ?: "unknown" }
        val text = evaluateJs(
            """
            (function() {
                var body = document.body ? document.body.innerText : '';
                return 'URL: ' + window.location.href + '\n\nPage content:\n' + body.substring(0, 3000);
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
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
        }

    fun getCurrentUrl(): String = webView.url ?: ""

    fun clearSession() {
        mainHandler.post {
            webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
