package com.personalai.craig.web

import android.util.Log
import com.personalai.craig.data.preferences.SecurePreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages authentication state for the OpticSEO website.
 * Handles login, session detection, and re-authentication on expiry.
 */
@Singleton
class OpticSEOSession @Inject constructor(
    private val manager: WebAutomationManager,
    private val prefs: SecurePreferences
) {
    companion object {
        private const val TAG = "OpticSEOSession"
        private const val BASE_URL = "https://www.opticseoservices.com"
        private val LOGIN_INDICATORS = listOf("login", "sign in", "log in", "password", "email", "signin")
        private val LOGGED_IN_INDICATORS = listOf("dashboard", "logout", "log out", "sign out", "account", "clients")
    }

    private var isLoggedIn = false

    /**
     * Ensures the session is authenticated. Logs in if needed.
     */
    suspend fun ensureLoggedIn(): Boolean {
        if (isLoggedIn) {
            val page = manager.readPageContent().lowercase()
            if (LOGGED_IN_INDICATORS.any { page.contains(it) }) return true
            isLoggedIn = false
        }

        return attemptLogin()
    }

    /**
     * Navigate to the OpticSEO home/dashboard.
     */
    suspend fun navigateHome(): String {
        ensureLoggedIn()
        return manager.navigate(BASE_URL)
    }

    private suspend fun attemptLogin(): Boolean {
        val username = prefs.opticSeoUsername.first()
        val password = prefs.opticSeoPassword.first()

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.e(TAG, "OpticSEO credentials not configured")
            return false
        }

        try {
            // Navigate to the site and check if already logged in
            manager.navigate(BASE_URL)
            val pageContent = manager.readPageContent().lowercase()

            if (LOGGED_IN_INDICATORS.any { pageContent.contains(it) }) {
                isLoggedIn = true
                return true
            }

            // Try to find and fill the login form
            // Common login form patterns — try email field first
            val emailSelectors = listOf(
                "input[type='email']",
                "input[name='email']",
                "input[id='email']",
                "input[name='username']",
                "input[type='text']"
            )
            var emailFilled = false
            for (sel in emailSelectors) {
                val result = manager.fillInput(sel, username)
                if (!result.contains("not found")) {
                    emailFilled = true
                    break
                }
            }

            if (!emailFilled) {
                Log.e(TAG, "Could not find email/username field on login page")
                return false
            }

            // Fill password
            manager.fillInput("input[type='password']", password)

            // Submit the form
            val submitSelectors = listOf(
                "button[type='submit']",
                "input[type='submit']",
                "button:contains('Login')",
                "button:contains('Sign in')"
            )
            var submitted = false
            for (sel in submitSelectors) {
                val result = manager.clickBySelector(sel)
                if (!result.contains("not found")) {
                    submitted = true
                    break
                }
            }
            if (!submitted) {
                manager.submitForm("form")
            }

            // Wait for post-login redirect
            kotlinx.coroutines.delay(2000)
            val postLoginPage = manager.readPageContent().lowercase()

            isLoggedIn = LOGGED_IN_INDICATORS.any { postLoginPage.contains(it) }
            if (!isLoggedIn) {
                Log.e(TAG, "Login may have failed — post-login indicators not found")
            }
            return isLoggedIn

        } catch (e: Exception) {
            Log.e(TAG, "Login attempt failed: ${e.message}", e)
            isLoggedIn = false
            return false
        }
    }

    fun logout() {
        isLoggedIn = false
        manager.clearSession()
    }
}
