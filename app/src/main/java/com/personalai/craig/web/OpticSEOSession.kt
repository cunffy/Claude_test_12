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
        const val APP_URL = "https://opticseoservices.com/app"
        private val LOGGED_IN_INDICATORS = listOf(
            "dashboard", "logout", "log out", "sign out", "clients", "reports", "settings"
        )
        private val LOGIN_INDICATORS = listOf("login", "sign in", "password", "email")
    }

    private var isLoggedIn = false

    suspend fun ensureLoggedIn(): Boolean {
        if (isLoggedIn) {
            // Re-verify the session is still active
            val url = manager.getCurrentUrl()
            if (url.contains("opticseoservices.com/app") && !url.contains("login")) {
                val page = manager.readPageContent().lowercase()
                if (LOGGED_IN_INDICATORS.any { page.contains(it) }) return true
            }
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

        try {
            // Navigate to the app — it will redirect to login if not authenticated
            manager.navigate(APP_URL)
            // Give SPA time to fully settle and execute authentication checks
            delay(4_000)

            val pageContent = manager.readPageContent().lowercase()
            val currentUrl  = manager.getCurrentUrl().lowercase()

            // Already logged in?
            if (LOGGED_IN_INDICATORS.any { pageContent.contains(it) } &&
                !LOGIN_INDICATORS.any { currentUrl.contains(it) }) {
                Log.i(TAG, "Already logged in")
                isLoggedIn = true
                return true
            }

            Log.i(TAG, "Login form expected — attempting to fill credentials")

            // Fill email / username field
            val emailSelectors = listOf(
                "input[type='email']",
                "input[name='email']",
                "input[id='email']",
                "input[name='username']",
                "input[placeholder*='email' i]",
                "input[type='text']"
            )
            var emailFilled = false
            for (sel in emailSelectors) {
                val r = manager.fillInput(sel, username)
                if (!r.contains("not found")) { emailFilled = true; break }
            }
            if (!emailFilled) {
                Log.e(TAG, "Could not find email field — page content:\n$pageContent")
                return false
            }

            manager.fillInput("input[type='password']", password)

            // Small delay so React/Vue state can update before we try to submit
            delay(400)

            // Submit
            val submitSelectors = listOf(
                "button[type='submit']",
                "input[type='submit']",
                "button"   // last resort — click first button
            )
            var submitted = false
            for (sel in submitSelectors) {
                val r = manager.clickBySelector(sel)
                if (!r.contains("not found")) { submitted = true; break }
            }
            if (!submitted) manager.submitForm("form")

            // Wait for post-login redirect and SPA render
            delay(5_000)

            val postPage = manager.readPageContent().lowercase()
            val postUrl  = manager.getCurrentUrl().lowercase()
            // Consider logged in if we're on the app domain and not stuck on a login page
            isLoggedIn = postUrl.contains("opticseoservices.com") &&
                    !postUrl.contains("login") &&
                    !LOGIN_INDICATORS.all { postPage.contains(it) }

            if (!isLoggedIn) Log.e(TAG, "Login seems to have failed — post-login page:\n${postPage.take(500)}")
            return isLoggedIn

        } catch (e: Exception) {
            Log.e(TAG, "Login attempt threw: ${e.message}", e)
            isLoggedIn = false
            return false
        }
    }

    suspend fun logout() {
        isLoggedIn = false
        manager.clearSession()
    }
}
