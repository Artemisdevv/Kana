package com.artemis.ebookreader.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal interface UpdatePreferences {
    val automatic: Flow<Boolean>
    suspend fun setAutomatic(enabled: Boolean)
    suspend fun reserveAutomaticCheck(now: Long): Boolean
}

internal class UpdateSettings(private val store: DataStore<Preferences>) : UpdatePreferences {
    override val automatic = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[automaticKey] ?: true }

    override suspend fun setAutomatic(enabled: Boolean) {
        store.edit { it[automaticKey] = enabled }
    }

    override suspend fun reserveAutomaticCheck(now: Long): Boolean {
        var reserved = false
        store.edit { preferences ->
            if (preferences[automaticKey] != false && automaticCheckDue(now, preferences[lastCheckKey])) {
                preferences[lastCheckKey] = now
                reserved = true
            }
        }
        return reserved
    }

    private companion object {
        val automaticKey = booleanPreferencesKey("automatic_update_checks")
        val lastCheckKey = longPreferencesKey("last_automatic_update_check")
    }
}

internal fun automaticCheckDue(now: Long, previous: Long?): Boolean =
    previous == null || now < previous || now - previous >= 24 * 60 * 60 * 1_000L
