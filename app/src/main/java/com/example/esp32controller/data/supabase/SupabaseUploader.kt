package com.example.esp32controller.data.supabase

import com.example.esp32controller.data.fsr.FsrLocalDatabase
import com.example.esp32controller.model.FsrMinuteRollup
import com.example.esp32controller.model.FsrSessionSummary
import com.example.esp32controller.model.SupabaseSettings
import com.example.esp32controller.model.SupabaseSyncState
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SupabaseUploader(
    private val database: FsrLocalDatabase,
    private val gson: Gson = Gson()
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(SupabaseSyncState())
    val state: StateFlow<SupabaseSyncState> = _state.asStateFlow()

    suspend fun sync(settings: SupabaseSettings) {
        _state.value = _state.value.copy(
            enabled = settings.enabled,
            configured = settings.configured
        )
        if (!settings.enabled) {
            _state.value = _state.value.copy(syncing = false, lastError = null, lastMessage = "云同步已关闭")
            return
        }
        if (!settings.configured) {
            _state.value = _state.value.copy(syncing = false, lastError = "Supabase 地址或 anon key 未填写")
            return
        }

        _state.value = _state.value.copy(syncing = true, lastError = null)
        runCatching {
            val sessions = database.pendingSessions(limit = 40)
            val minutes = database.pendingMinuteRollups(limit = 80)
            if (sessions.isNotEmpty()) uploadSessions(settings, sessions)
            if (minutes.isNotEmpty()) uploadMinutes(settings, minutes)
            _state.value = _state.value.copy(
                syncing = false,
                lastSyncAtMs = System.currentTimeMillis(),
                lastMessage = "已同步 ${sessions.size} 条会话、${minutes.size} 条分钟数据",
                lastError = null
            )
        }.onFailure { throwable ->
            _state.value = _state.value.copy(
                syncing = false,
                lastError = throwable.message ?: "Supabase 同步失败"
            )
        }
    }

    private fun uploadMinutes(settings: SupabaseSettings, rows: List<FsrMinuteRollup>) {
        val payload = rows.map { rollup ->
            mapOf(
                "id" to rollup.remoteId,
                "session_id" to rollup.sessionId,
                "minute_start_ms" to rollup.minuteStartMs,
                "device_mac" to rollup.deviceMac,
                "device_name" to rollup.deviceName,
                "samples" to rollup.samples,
                "sensor_values" to parseJson(rollup.valuesJson),
                "summary" to rollup.summary
            )
        }
        postRows(settings, settings.minuteDataTable, payload, onConflict = "id")
        database.markMinuteRollupsUploaded(rows.map { it.localId }, System.currentTimeMillis())
    }

    private fun uploadSessions(settings: SupabaseSettings, rows: List<FsrSessionSummary>) {
        val payload = rows.map { session ->
            mapOf(
                "id" to session.id,
                "start_ms" to session.startMs,
                "end_ms" to session.endMs,
                "duration_ms" to session.durationMs,
                "avg_pressure" to session.avgPressure,
                "max_pressure" to session.maxPressure,
                "hug_count" to session.hugCount,
                "poke_count" to session.pokeCount,
                "pinch_count" to session.pinchCount,
                "stroke_count" to session.strokeCount,
                "press_count" to session.pressCount,
                "summary" to session.summary,
                "updated_at_ms" to session.updatedAtMs
            )
        }
        postRows(settings, settings.sessionsTable, payload, onConflict = "id")
        database.markSessionsUploaded(rows.map { it.id })
    }

    private fun postRows(
        settings: SupabaseSettings,
        table: String,
        payload: Any,
        onConflict: String
    ) {
        val base = settings.projectUrl.trim().trimEnd('/')
        val safeTable = table.trim().ifBlank { error("Supabase 表名为空") }
        val url = "$base/rest/v1/$safeTable?on_conflict=$onConflict"
        val body = gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", settings.anonKey.trim())
            .addHeader("Authorization", "Bearer ${settings.anonKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty().take(240)
                error("Supabase ${response.code}: $errorBody")
            }
        }
    }

    private fun parseJson(raw: String): Any {
        return runCatching { JsonParser.parseString(raw) }.getOrElse { raw }
    }
}
