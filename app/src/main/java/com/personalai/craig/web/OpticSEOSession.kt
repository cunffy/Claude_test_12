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
            // Go directly to the login page — no SPA-redirect uncertainty
            manager.navigate(LOGIN_URL)

            // Single combined waitForElement check — waits up to 8 s for ANY email
            // input variant to appear. Using one wait avoids 7 × 6 s = 42 s timeouts.
            val wait = manager.waitForElement(EMAIL_COMBINED, timeoutMs = 8_000)
            val emailFieldPresent = wait.contains("element found")
            val currentUrl = manager.getCurrentUrl().lowercase()

            if (!emailFieldPresent) {
                // If there's no login form we may already be logged in (cookie session)
                if (currentUrl.contains("opticseoservices.com") && !currentUrl.contains("login")) {
                    Log.i(TAG, "No login form — already authenticated via cookie")
                    isLoggedIn = true
                    return true
                }
                Log.e(TAG, "Login form not found on $currentUrl — cannot log in")
                return false
            }

            Log.i(TAG, "Login form detected — filling credentials")

            // Fill email
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

            // Let React/Vue state settle before submitting
            delay(500)

            // Submit — try typed submit buttons first, fall back to any button
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
                    break
                }
            }

            // Wait for the post-login redirect (SPA may animate the transition)
            delay(6_000)

            val postUrl = manager.getCurrentUrl().lowercase()
            isLoggedIn = postUrl.contains("opticseoservices.com") && !postUrl.contains("login")

            if (!isLoggedIn) {
                // Slow redirect — give it a bit more time
                delay(4_000)
                val finalUrl = manager.getCurrentUrl().lowercase()
                isLoggedIn = finalUrl.contains("opticseoservices.com") && !finalUrl.contains("login")
            }

            if (isLoggedIn) {
                Log.i(TAG, "Login succeeded — now at ${manager.getCurrentUrl()}")
            } else {
                val pageSnippet = manager.readPageContent().take(300)
                Log.e(TAG, "Login failed — page: $pageSnippet")
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
