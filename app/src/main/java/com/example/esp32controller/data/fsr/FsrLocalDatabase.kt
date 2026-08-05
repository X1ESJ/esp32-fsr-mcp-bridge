package com.example.esp32controller.data.fsr

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import com.example.esp32controller.model.FsrDatabaseStats
import com.example.esp32controller.model.FsrLocalEvent
import com.example.esp32controller.model.FsrMinuteRollup
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.FsrSessionSummary
import com.example.esp32controller.model.FsrWindowResult
import com.example.esp32controller.model.StoredDevice
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

private const val DATABASE_NAME = "fsr_local_history.db"
private const val DATABASE_VERSION = 1

class FsrLocalDatabase private constructor(
    context: Context,
    private val gson: Gson = Gson()
) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fsr_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                t INTEGER NOT NULL,
                device_mac TEXT,
                device_name TEXT,
                device_ip TEXT,
                values_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_fsr_samples_t ON fsr_samples(t)")

        db.execSQL(
            """
            CREATE TABLE fsr_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                type TEXT NOT NULL,
                sensors_json TEXT NOT NULL,
                peak_value INTEGER NOT NULL,
                avg_value INTEGER NOT NULL,
                summary TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_fsr_events_time ON fsr_events(start_ms, end_ms)")
        db.execSQL("CREATE INDEX idx_fsr_events_session ON fsr_events(session_id)")

        db.execSQL(
            """
            CREATE TABLE fsr_sessions (
                id TEXT PRIMARY KEY,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                avg_pressure REAL NOT NULL,
                max_pressure INTEGER NOT NULL,
                hug_count INTEGER NOT NULL,
                poke_count INTEGER NOT NULL,
                pinch_count INTEGER NOT NULL,
                stroke_count INTEGER NOT NULL,
                press_count INTEGER NOT NULL,
                summary TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_fsr_sessions_time ON fsr_sessions(start_ms, end_ms)")
        db.execSQL("CREATE INDEX idx_fsr_sessions_uploaded ON fsr_sessions(uploaded)")

        db.execSQL(
            """
            CREATE TABLE fsr_minute_rollups (
                local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL,
                minute_start_ms INTEGER NOT NULL,
                device_mac TEXT,
                device_name TEXT,
                samples INTEGER NOT NULL,
                values_json TEXT NOT NULL,
                summary TEXT NOT NULL,
                uploaded INTEGER NOT NULL DEFAULT 0,
                uploaded_at_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_fsr_minutes_time ON fsr_minute_rollups(minute_start_ms)")
        db.execSQL("CREATE INDEX idx_fsr_minutes_uploaded ON fsr_minute_rollups(uploaded)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS fsr_samples")
        db.execSQL("DROP TABLE IF EXISTS fsr_events")
        db.execSQL("DROP TABLE IF EXISTS fsr_sessions")
        db.execSQL("DROP TABLE IF EXISTS fsr_minute_rollups")
        onCreate(db)
    }

    @Synchronized
    fun insertSample(
        t: Long,
        device: StoredDevice?,
        readings: List<FsrSensorReading>
    ) {
        if (readings.isEmpty()) return
        val values = readings
            .sortedWith(compareBy<FsrSensorReading> { it.pin ?: Int.MAX_VALUE }.thenBy { it.name })
            .associate { reading -> reading.name to reading.value }

        writableDatabase.insert(
            "fsr_samples",
            null,
            ContentValues().apply {
                put("t", t)
                put("device_mac", device?.macAddress)
                put("device_name", device?.name)
                put("device_ip", device?.ipAddress)
                put("values_json", gson.toJson(values))
            }
        )
    }

    @Synchronized
    fun insertEvent(event: FsrLocalEvent): Long {
        return writableDatabase.insert(
            "fsr_events",
            null,
            ContentValues().apply {
                put("session_id", event.sessionId)
                put("start_ms", event.startMs)
                put("end_ms", event.endMs)
                put("type", event.type)
                put("sensors_json", gson.toJson(event.sensors))
                put("peak_value", event.peakValue)
                put("avg_value", event.avgValue)
                put("summary", event.summary)
            }
        )
    }

    @Synchronized
    fun upsertSession(summary: FsrSessionSummary) {
        writableDatabase.insertWithOnConflict(
            "fsr_sessions",
            null,
            ContentValues().apply {
                put("id", summary.id)
                put("start_ms", summary.startMs)
                put("end_ms", summary.endMs)
                put("duration_ms", summary.durationMs)
                put("avg_pressure", summary.avgPressure)
                put("max_pressure", summary.maxPressure)
                put("hug_count", summary.hugCount)
                put("poke_count", summary.pinchCount)
                put("pinch_count", summary.pinchCount)
                put("stroke_count", summary.strokeCount)
                put("press_count", summary.pressCount)
                put("summary", summary.summary)
                put("updated_at_ms", summary.updatedAtMs)
                put("uploaded", 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun upsertMinuteRollup(rollup: FsrMinuteRollup) {
        writableDatabase.insertWithOnConflict(
            "fsr_minute_rollups",
            null,
            ContentValues().apply {
                put("remote_id", rollup.remoteId)
                put("session_id", rollup.sessionId)
                put("minute_start_ms", rollup.minuteStartMs)
                put("device_mac", rollup.deviceMac)
                put("device_name", rollup.deviceName)
                put("samples", rollup.samples)
                put("values_json", rollup.valuesJson)
                put("summary", rollup.summary)
                put("uploaded", 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun getStats(): FsrDatabaseStats {
        val db = readableDatabase
        val sampleRows = db.countRows("fsr_samples")
        val eventRows = db.countRows("fsr_events")
        val sessionRows = db.countRows("fsr_sessions")
        val minuteRows = db.countRows("fsr_minute_rollups")
        val pendingUploads = db.countRows("fsr_minute_rollups", "uploaded=0") +
            db.countRows("fsr_sessions", "uploaded=0")
        val lastSampleAtMs = db.rawQuery("SELECT MAX(t) FROM fsr_samples", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        return FsrDatabaseStats(
            sampleRows = sampleRows,
            eventRows = eventRows,
            sessionRows = sessionRows,
            minuteRows = minuteRows,
            pendingUploads = pendingUploads,
            lastSampleAtMs = lastSampleAtMs
        )
    }

    @Synchronized
    fun querySessions(limit: Int, sinceMs: Long? = null): List<FsrSessionSummary> {
        val where = if (sinceMs == null) null else "end_ms>=?"
        val args = if (sinceMs == null) null else arrayOf(sinceMs.toString())
        return readableDatabase.query(
            "fsr_sessions",
            null,
            where,
            args,
            null,
            null,
            "updated_at_ms DESC",
            limit.coerceIn(1, 100).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toSessionSummary())
            }
        }
    }

    @Synchronized
    fun queryEvents(
        fromMs: Long,
        toMs: Long,
        names: Set<String>,
        type: String?,
        sessionId: String?,
        limit: Int
    ): List<FsrLocalEvent> {
        val clauses = mutableListOf("end_ms>=?", "start_ms<=?")
        val args = mutableListOf(fromMs.toString(), toMs.toString())
        if (!type.isNullOrBlank()) {
            clauses += "type=?"
            args += type
        }
        if (!sessionId.isNullOrBlank()) {
            clauses += "session_id=?"
            args += sessionId
        }
        return readableDatabase.query(
            "fsr_events",
            null,
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "start_ms DESC",
            limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val event = cursor.toEvent()
                    if (names.isEmpty() || event.sensors.any { sensor -> names.any { it.equals(sensor, ignoreCase = true) } }) {
                        add(event)
                    }
                }
            }.asReversed()
        }
    }

    @Synchronized
    fun queryMinuteWindow(
        fromMs: Long,
        toMs: Long,
        limit: Int
    ): FsrWindowResult {
        val rows = readableDatabase.query(
            "fsr_minute_rollups",
            null,
            "minute_start_ms BETWEEN ? AND ?",
            arrayOf(fromMs.toString(), toMs.toString()),
            null,
            null,
            "minute_start_ms ASC",
            limit.coerceIn(1, 1440).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getLong("minute_start_ms") - fromMs,
                            cursor.getString("summary"),
                            cursor.getInt("samples")
                        )
                    )
                }
            }
        }
        return FsrWindowResult(
            fromMs = fromMs,
            toMs = toMs,
            mode = "minute",
            cols = listOf("dt", "summary", "n"),
            rows = rows
        )
    }

    @Synchronized
    fun pendingMinuteRollups(limit: Int): List<FsrMinuteRollup> {
        return readableDatabase.query(
            "fsr_minute_rollups",
            null,
            "uploaded=0",
            null,
            null,
            null,
            "minute_start_ms ASC",
            limit.coerceIn(1, 100).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toMinuteRollup())
            }
        }
    }

    @Synchronized
    fun pendingSessions(limit: Int): List<FsrSessionSummary> {
        return readableDatabase.query(
            "fsr_sessions",
            null,
            "uploaded=0",
            null,
            null,
            null,
            "updated_at_ms ASC",
            limit.coerceIn(1, 100).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toSessionSummary())
            }
        }
    }

    @Synchronized
    fun markMinuteRollupsUploaded(localIds: List<Long>, uploadedAtMs: Long) {
        if (localIds.isEmpty()) return
        val placeholders = localIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE fsr_minute_rollups SET uploaded=1, uploaded_at_ms=? WHERE local_id IN ($placeholders)",
            (listOf(uploadedAtMs) + localIds).map { it.toString() }.toTypedArray()
        )
    }

    @Synchronized
    fun markSessionsUploaded(ids: List<String>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE fsr_sessions SET uploaded=1 WHERE id IN ($placeholders)",
            ids.toTypedArray()
        )
    }

    @Synchronized
    fun exportToJson(context: Context): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "fsr-exports"
        )
        dir.mkdirs()
        val file = File(dir, "fsr-export-${System.currentTimeMillis()}.json")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("{")
            writer.write("\"exportId\":")
            writer.write(gson.toJson(UUID.randomUUID().toString()))
            writer.write(",\"exportedAtMs\":${System.currentTimeMillis()}")
            writer.write(",\"samples\":")
            writeRows(writer, "fsr_samples", "t ASC") { cursor ->
                mapOf(
                    "t" to cursor.getLong("t"),
                    "deviceMac" to cursor.getStringOrNull("device_mac"),
                    "deviceName" to cursor.getStringOrNull("device_name"),
                    "deviceIp" to cursor.getStringOrNull("device_ip"),
                    "values" to parseJson(cursor.getString("values_json"))
                )
            }
            writer.write(",\"minuteRollups\":")
            writeRows(writer, "fsr_minute_rollups", "minute_start_ms ASC") { cursor ->
                mapOf(
                    "id" to cursor.getString("remote_id"),
                    "sessionId" to cursor.getString("session_id"),
                    "minuteStartMs" to cursor.getLong("minute_start_ms"),
                    "deviceMac" to cursor.getStringOrNull("device_mac"),
                    "deviceName" to cursor.getStringOrNull("device_name"),
                    "samples" to cursor.getInt("samples"),
                    "values" to parseJson(cursor.getString("values_json")),
                    "summary" to cursor.getString("summary")
                )
            }
            writer.write(",\"events\":")
            writeRows(writer, "fsr_events", "start_ms ASC") { cursor ->
                mapOf(
                    "id" to cursor.getLong("id"),
                    "sessionId" to cursor.getString("session_id"),
                    "startMs" to cursor.getLong("start_ms"),
                    "endMs" to cursor.getLong("end_ms"),
                    "type" to cursor.getString("type"),
                    "sensors" to parseStringList(cursor.getString("sensors_json")),
                    "peakValue" to cursor.getInt("peak_value"),
                    "avgValue" to cursor.getInt("avg_value"),
                    "summary" to cursor.getString("summary")
                )
            }
            writer.write(",\"sessions\":")
            writeRows(writer, "fsr_sessions", "start_ms ASC") { cursor ->
                mapOf(
                    "id" to cursor.getString("id"),
                    "startMs" to cursor.getLong("start_ms"),
                    "endMs" to cursor.getLong("end_ms"),
                    "durationMs" to cursor.getLong("duration_ms"),
                    "avgPressure" to cursor.getFloat("avg_pressure"),
                    "maxPressure" to cursor.getInt("max_pressure"),
                    "hugCount" to cursor.getInt("hug_count"),
                    "pokeCount" to cursor.getInt("poke_count"),
                    "pinchCount" to cursor.getInt("pinch_count"),
                    "strokeCount" to cursor.getInt("stroke_count"),
                    "pressCount" to cursor.getInt("press_count"),
                    "summary" to cursor.getString("summary")
                )
            }
            writer.write("}")
        }
        return file
    }

    private fun writeRows(
        writer: java.io.Writer,
        table: String,
        orderBy: String,
        mapper: (Cursor) -> Map<String, Any?>
    ) {
        writer.write("[")
        readableDatabase.query(table, null, null, null, null, null, orderBy).use { cursor ->
            var first = true
            while (cursor.moveToNext()) {
                if (!first) writer.write(",")
                first = false
                writer.write(gson.toJson(mapper(cursor)))
            }
        }
        writer.write("]")
    }

    private fun parseJson(raw: String): Any {
        return runCatching { JsonParser.parseString(raw) }.getOrElse { raw }
    }

    private fun parseStringList(raw: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return runCatching<List<String>> { gson.fromJson(raw, type) ?: emptyList() }.getOrDefault(emptyList())
    }

    private fun Cursor.toEvent(): FsrLocalEvent {
        return FsrLocalEvent(
            id = getLong("id"),
            sessionId = getString("session_id"),
            startMs = getLong("start_ms"),
            endMs = getLong("end_ms"),
            type = getString("type"),
            sensors = parseStringList(getString("sensors_json")),
            peakValue = getInt("peak_value"),
            avgValue = getInt("avg_value"),
            summary = getString("summary")
        )
    }

    private fun Cursor.toSessionSummary(): FsrSessionSummary {
        return FsrSessionSummary(
            id = getString("id"),
            startMs = getLong("start_ms"),
            endMs = getLong("end_ms"),
            durationMs = getLong("duration_ms"),
            avgPressure = getFloat("avg_pressure"),
            maxPressure = getInt("max_pressure"),
            hugCount = getInt("hug_count"),
            pokeCount = getInt("poke_count"),
            pinchCount = getInt("pinch_count"),
            strokeCount = getInt("stroke_count"),
            pressCount = getInt("press_count"),
            summary = getString("summary"),
            updatedAtMs = getLong("updated_at_ms")
        )
    }

    private fun Cursor.toMinuteRollup(): FsrMinuteRollup {
        return FsrMinuteRollup(
            localId = getLong("local_id"),
            remoteId = getString("remote_id"),
            sessionId = getString("session_id"),
            minuteStartMs = getLong("minute_start_ms"),
            deviceMac = getStringOrNull("device_mac"),
            deviceName = getStringOrNull("device_name"),
            samples = getInt("samples"),
            valuesJson = getString("values_json"),
            summary = getString("summary"),
            uploaded = getInt("uploaded") == 1
        )
    }

    private fun SQLiteDatabase.countRows(table: String, where: String? = null): Long {
        val sql = if (where == null) "SELECT COUNT(*) FROM $table" else "SELECT COUNT(*) FROM $table WHERE $where"
        return rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun Cursor.getString(column: String): String {
        return getString(getColumnIndexOrThrow(column)).orEmpty()
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLong(column: String): Long {
        return getLong(getColumnIndexOrThrow(column))
    }

    private fun Cursor.getInt(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }

    private fun Cursor.getFloat(column: String): Float {
        return getFloat(getColumnIndexOrThrow(column))
    }

    companion object {
        @Volatile private var instance: FsrLocalDatabase? = null

        fun get(context: Context, gson: Gson = Gson()): FsrLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: FsrLocalDatabase(context.applicationContext, gson).also { instance = it }
            }
        }
    }
}
