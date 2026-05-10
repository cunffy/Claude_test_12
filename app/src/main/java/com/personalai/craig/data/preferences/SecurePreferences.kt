package com.personalai.craig.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "craig_prefs")

@Singleton
class SecurePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val CLAUDE_API_KEY      = stringPreferencesKey("claude_api_key")
        val ASSISTANT_NAME      = stringPreferencesKey("assistant_name")
        val VOICE_GENDER        = stringPreferencesKey("voice_gender")
        val OPTICSEO_USERNAME   = stringPreferencesKey("opticseo_username")
        val OPTICSEO_PASSWORD   = stringPreferencesKey("opticseo_password")
        val SETUP_COMPLETE      = booleanPreferencesKey("setup_complete")
        val BRIEFING_COMPLETE   = booleanPreferencesKey("briefing_complete")
    }

    val claudeApiKey: Flow<String?>       = context.dataStore.data.map { it[CLAUDE_API_KEY] }
    val assistantName: Flow<String>       = context.dataStore.data.map { it[ASSISTANT_NAME] ?: "Craig" }
    val voiceGender: Flow<String>         = context.dataStore.data.map { it[VOICE_GENDER] ?: "male" }
    val opticSeoUsername: Flow<String?>   = context.dataStore.data.map { it[OPTICSEO_USERNAME] }
    val opticSeoPassword: Flow<String?>   = context.dataStore.data.map { it[OPTICSEO_PASSWORD] }
    val isSetupComplete: Flow<Boolean>    = context.dataStore.data.map { it[SETUP_COMPLETE] ?: false }
    val isBriefingComplete: Flow<Boolean> = context.dataStore.data.map { it[BRIEFING_COMPLETE] ?: false }

    suspend fun setBriefingComplete() {
        context.dataStore.edit { it[BRIEFING_COMPLETE] = true }
    }

    suspend fun saveSetupData(
        claudeKey: String,
        assistantName: String,
        voiceGender: String,
        opticSeoUsername: String,
        opticSeoPassword: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[CLAUDE_API_KEY]    = claudeKey
            prefs[ASSISTANT_NAME]    = assistantName
            prefs[VOICE_GENDER]      = voiceGender
            prefs[OPTICSEO_USERNAME] = opticSeoUsername
            prefs[OPTICSEO_PASSWORD] = opticSeoPassword
            prefs[SETUP_COMPLETE]    = true
            prefs[BRIEFING_COMPLETE] = true
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
