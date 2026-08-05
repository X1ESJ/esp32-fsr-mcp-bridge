package com.example.esp32controller.data.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.esp32controller.model.DEFAULT_FSR_HISTORY_WINDOW_MS
import com.example.esp32controller.model.DEFAULT_FSR_SAMPLE_INTERVAL_MS
import com.example.esp32controller.model.DEFAULT_FSR_TRIGGER_THRESHOLD
import com.example.esp32controller.model.FsrBridgeSettings
import com.example.esp32controller.model.SupabaseSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SETTINGS_DATASTORE_NAME = "fsr_bridge_settings"
private val Context.fsrSettingsDataStore by preferencesDataStore(name = SETTINGS_DATASTORE_NAME)

class FsrSettingsStore(
    private val context: Context
) {
    private val historyWindowMsKey = longPreferencesKey("history_window_ms")
    private val sampleIntervalMsKey = longPreferencesKey("sample_interval_ms")
    private val triggerThresholdKey = intPreferencesKey("trigger_threshold")
    private val supabaseEnabledKey = booleanPreferencesKey("supabase_enabled")
    private val supabaseProjectUrlKey = stringPreferencesKey("supabase_project_url")
    private val supabaseAnonKeyKey = stringPreferencesKey("supabase_anon_key")
    private val supabaseMinuteTableKey = stringPreferencesKey("supabase_minute_table")
    private val supabaseSessionsTableKey = stringPreferencesKey("supabase_sessions_table")

    val settingsFlow: Flow<FsrBridgeSettings> = context.fsrSettingsDataStore.data.map { preferences ->
        FsrBridgeSettings(
            historyWindowMs = (preferences[historyWindowMsKey] ?: DEFAULT_FSR_HISTORY_WINDOW_MS)
                .coerceIn(5_000L, 24 * 60 * 60 * 1000L),
            sampleIntervalMs = (preferences[sampleIntervalMsKey] ?: DEFAULT_FSR_SAMPLE_INTERVAL_MS)
                .coerceIn(100L, 60_000L),
            triggerThreshold = (preferences[triggerThresholdKey] ?: DEFAULT_FSR_TRIGGER_THRESHOLD)
                .coerceIn(1, 4095),
            supabase = SupabaseSettings(
                enabled = preferences[supabaseEnabledKey] ?: false,
                projectUrl = preferences[supabaseProjectUrlKey].orEmpty(),
                anonKey = preferences[supabaseAnonKeyKey].orEmpty(),
                minuteDataTable = preferences[supabaseMinuteTableKey].orEmpty().ifBlank { "fsr_minute_data" },
                sessionsTable = preferences[supabaseSessionsTableKey].orEmpty().ifBlank { "fsr_sessions" }
            )
        )
    }

    suspend fun updateHistoryWindowMs(value: Long) {
        context.fsrSettingsDataStore.edit { preferences ->
            preferences[historyWindowMsKey] = value.coerceIn(5_000L, 24 * 60 * 60 * 1000L)
        }
    }

    suspend fun updateSampleIntervalMs(value: Long) {
        context.fsrSettingsDataStore.edit { preferences ->
            preferences[sampleIntervalMsKey] = value.coerceIn(100L, 60_000L)
        }
    }

    suspend fun updateTriggerThreshold(value: Int) {
        context.fsrSettingsDataStore.edit { preferences ->
            preferences[triggerThresholdKey] = value.coerceIn(1, 4095)
        }
    }

    suspend fun updateSupabase(settings: SupabaseSettings) {
        context.fsrSettingsDataStore.edit { preferences ->
            preferences[supabaseEnabledKey] = settings.enabled
            preferences[supabaseProjectUrlKey] = settings.projectUrl.trim()
            preferences[supabaseAnonKeyKey] = settings.anonKey.trim()
            preferences[supabaseMinuteTableKey] = settings.minuteDataTable.trim().ifBlank { "fsr_minute_data" }
            preferences[supabaseSessionsTableKey] = settings.sessionsTable.trim().ifBlank { "fsr_sessions" }
        }
    }
}
