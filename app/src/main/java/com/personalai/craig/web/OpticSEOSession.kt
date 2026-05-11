package com.personalai.craig.web

import android.util.Log
import com.personalai.craig.data.preferences.SecurePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpticSEOSession @Inject constructor(
    private val manager: WebAutomationManager,
    private val prefs: SecurePreferences
) {
    companion object {
        private const val TAG = "OpticSEOSession"
        const val APP_URL   = "https://www.opticseoservices.com/app"
        const val LOGIN_URL = "https://www.opticseoservices.com/login"
        private val LOGGED_IN_INDICATORS = listOf("dashboard", "logout", "log out", "sign out")
        // Combined CSS selector — querySelector() matches the FIRST element of ANY selector.
        // This lets waitForElement() check all variants in a single 8-second poll window
        // instead of 7 × 6 seconds = 42 seconds of sequential waits.
        private const val EMAIL_COMBINED =
            "input[type='email'], input[name='email'], input[id='email'], " +
            "input[name='username'], input[autocomplete='email'], input[type='text']"
        private val EMAIL_SELECTORS = listOf(
            "input[type='email']",
            "input[name='email']",
            "input[id='email']",
            "input[name='username']",
            "input[autocomplete='email']",
            "input[placeholder*='email' i]",
            "input[type='text']"
        )
    }

    @Volatile private var isLoggedIn = false

    suspend fun ensureLoggedIn(): Boolean {
        if (isLoggedIn) {
            val url = manager.getCurrentUrl().lowercase()
            if (url.contains("opticseoservices.com") && !url.contains("login")) return true
            isLoggedIn = false
        }
        return attemptLogin()
    }

    suspend fun navigateToApp(): String {
        ensureLoggedIn()
        return manager.navigate(APP_URL)
    }

    private suspend fun attemptLogin(): Boolean {
        val username = prefs.opticSeoUsername.first()
        val password  = prefs.opticSeoPassword.first()

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.e(TAG, "OpticSEO credentials not configured")
            return false
        }

        return try {
            // Navigate to the app URL first — if cookies are still valid the site skips the
            // login page entirely and we're done. Navigating to /login directly misses this.
            manager.navigate(APP_URL)
            delay(1_500)

            var currentUrl = manager.getCurrentUrl().lowercase()

            if (currentUrl.contains("opticseoservices.com") && !currentUrl.contains("login")) {
                Log.i(TAG, "Cookie session valid — already on app at $currentUrl")
                isLoggedIn = true
                return true
            }

            // If the site didn't redirect us to a login page, force navigate there
            if (!currentUrl.contains("login")) {
                manager.navigate(LOGIN_URL)
                delay(1_000)
            }

            // Wait for the email field — generous timeout for slow SPA rendering
            val wait = manager.waitForElement(EMAIL_COMBINED, timeoutMs = 12_000)
            val emailFieldPresent = wait.contains("element found")
            currentUrl = manager.getCurrentUrl().lowercase()

            if (!emailFieldPresent) {
                if (currentUrl.contains("opticseoservices.com") && !currentUrl.contains("login")) {
                    Log.i(TAG, "Redirected to app without showing login form — treating as logged in")
                    isLoggedIn = true
                    return true
                }
                Log.e(TAG, "Login form not found on $currentUrl")
                return false
            }

            Log.i(TAG, "Login form detected — filling credentials")

            // Fill email — try each selector until one works
            var emailFilled = false
            for (sel in EMAIL_SELECTORS) {
                val r = manager.fillInput(sel, username)
                if (!r.contains("not found")) { emailFilled = true; break }
            }
            if (!emailFilled) {
                Log.e(TAG, "Could not fill email field")
                return false
            }

            // Fill password
            manager.fillInput("input[type='password']", password)
            delay(800)

            // Submit — try typed submit buttons first, then form.submit() as fallback
            var submitted = false
            val submitSelectors = listOf(
                "button[type='submit']",
                "input[type='submit']",
                "button.login",
                "button.signin",
                "button.submit",
                "button"
            )
            for (sel in submitSelectors) {
                val r = manager.clickBySelector(sel)
                if (!r.contains("not found")) {
                    Log.i(TAG, "Submit clicked via $sel")
                    submitted = true
                    break
                }
            }
            if (!submitted) {
                // Last resort: trigger native form submission via JS
                manager.submitForm("form")
                Log.i(TAG, "Submit via form.submit() fallback")
            }

            // Poll for post-login redirect instead of a fixed delay —
            // fires as soon as the site lands on the app page (up to 20 s).
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline) {
                delay(700)
                val url = manager.getCurrentUrl().lowercase()
                if (url.contains("opticseoservices.com") && !url.contains("login")) {
                    isLoggedIn = true
                    break
                }
            }

            if (isLoggedIn) {
                Log.i(TAG, "Login succeeded — now at ${manager.getCurrentUrl()}")
            } else {
                val pageSnippet = manager.readPageContent().take(300)
                Log.e(TAG, "Login failed — still on login page: $pageSnippet")
            }

            isLoggedIn
        } catch (e: Exception) {
            Log.e(TAG, "Login threw: ${e.message}", e)
            isLoggedIn = false
            false
        }
    }

    suspend fun logout() {
        isLoggedIn = false
        manager.clearSession()
    }
}
