package com.example.esp32controller.data.fsr

import android.content.Context
import android.net.Uri
import com.example.esp32controller.data.mcp.toMcpSensor
import com.example.esp32controller.model.DEFAULT_FSR_HISTORY_WINDOW_MS
import com.example.esp32controller.model.FSR_ANALOG_MAX_VALUE
import com.example.esp32controller.model.FsrBridgeSettings
import com.example.esp32controller.model.FsrChangeEvent
import com.example.esp32controller.model.FsrChangesResult
import com.example.esp32controller.model.FsrDatabaseStats
import com.example.esp32controller.model.FsrHistoryPoint
import com.example.esp32controller.model.FsrHistoryResult
import com.example.esp32controller.model.FsrHistorySegment
import com.example.esp32controller.model.FsrHistorySeries
import com.example.esp32controller.model.FsrLocalEvent
import com.example.esp32controller.model.FsrMcpSnapshot
import com.example.esp32controller.model.FsrSessionSummary
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.FsrWindowResult
import com.example.esp32controller.model.McpServerState
import com.example.esp32controller.model.PIN_DIRECTION_INPUT
import com.example.esp32controller.model.PIN_MODE_ANALOG
import com.example.esp32controller.model.PinConfig
import com.example.esp32controller.model.PinHistoryPoint
import com.example.esp32controller.model.StoredDevice
import com.example.esp32controller.model.SupabaseSyncState
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_CHANGE_DELTA = 8
private const val DEFAULT_COMPRESSION_TOLERANCE = 15
private const val MAX_CHANGE_EVENTS = 2400
private const val CACHE_DIR_NAME = "fsr-cache"
private const val CACHE_FILE_NAME = "recent_sensor_cache.json"
private const val DATABASE_STATS_REFRESH_MS = 5_000L
private const val CHART_WINDOW_SECONDS = 60

data class FsrBridgeState(
    val selectedDevice: StoredDevice? = null,
    val deviceOnline: Boolean = false,
    val deviceLedOn: Boolean = false,
    val sensorConfigs: List<PinConfig> = emptyList(),
    val sensorReadings: List<FsrSensorReading> = emptyList(),
    val sensorHistory: Map<String, List<PinHistoryPoint>> = emptyMap(),
    val mcpState: McpServerState = McpServerState(),
    val bleWifiError: String? = null,
    val settings: FsrBridgeSettings = FsrBridgeSettings(),
    val databaseStats: FsrDatabaseStats = FsrDatabaseStats(),
    val supabaseSyncState: SupabaseSyncState = SupabaseSyncState()
)

object FsrDataHub {
    private val gson = Gson()
    private val lock = Any()
    private val latestReadings = linkedMapOf<String, FsrSensorReading>()
    private val latestConfigs = linkedMapOf<String, PinConfig>()
    private val history = linkedMapOf<String, ArrayDeque<FsrHistoryPoint>>()
    private val chartHistory = linkedMapOf<String, ArrayDeque<PinHistoryPoint>>()
    private val changeEvents = ArrayDeque<FsrChangeEvent>()
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fsr-cache-writer").apply { isDaemon = true }
    }
    @Volatile private var cacheFile: File? = null
    @Volatile private var database: FsrLocalDatabase? = null
    @Volatile private var recorder: FsrHistoryRecorder? = null
    @Volatile private var initialized = false
    @Volatile private var currentSettings = FsrBridgeSettings()
    private var nextSequence = 1L
    private var lastSampleRecordedAt = 0L
    private var lastStatsPublishedAt = 0L
    private var chartStartedAt = 0L

    private val _state = MutableStateFlow(FsrBridgeState())
    val state: StateFlow<FsrBridgeState> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        val file = File(File(context.filesDir, CACHE_DIR_NAME), CACHE_FILE_NAME)
        synchronized(lock) {
            if (initialized) return
            cacheFile = file
            database = FsrLocalDatabase.get(context, gson)
            recorder = FsrHistoryRecorder(database = database!!, gson = gson).also { it.configure(currentSettings) }
            runCatching { file.parentFile?.mkdirs() }
            loadCacheLocked(file, System.currentTimeMillis())
            initialized = true
        }
        refreshDatabaseStats(force = true)
        publishState()
    }

    fun configure(settings: FsrBridgeSettings) {
        synchronized(lock) {
            currentSettings = settings
            recorder?.configure(settings)
            trimHistory(System.currentTimeMillis())
        }
        _state.update { it.copy(settings = settings) }
        publishState()
    }

    fun updateMcpState(mcpState: McpServerState) {
        _state.update { it.copy(mcpState = mcpState) }
    }

    fun updateSupabaseSyncState(syncState: SupabaseSyncState) {
        _state.update { it.copy(supabaseSyncState = syncState) }
    }

    fun updateMcpEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                mcpState = if (enabled) {
                    it.mcpState.copy(enabled = true)
                } else {
                    it.mcpState.copy(
                        enabled = false,
                        running = false,
                        error = "MCP 服务已关闭"
                    )
                }
            )
        }
    }

    fun updateSelectedDevice(device: StoredDevice?) {
        var shouldPersist = false
        _state.update {
            if (device == null) {
                synchronized(lock) {
                    latestConfigs.clear()
                    latestReadings.clear()
                    history.clear()
                    chartHistory.clear()
                    changeEvents.clear()
                    nextSequence = 1L
                    chartStartedAt = 0L
                    shouldPersist = true
                }
                it.copy(
                    selectedDevice = null,
                    deviceOnline = false,
                    deviceLedOn = false,
                    sensorConfigs = emptyList(),
                    sensorReadings = emptyList(),
                    sensorHistory = emptyMap(),
                    bleWifiError = null
                )
            } else {
                it.copy(selectedDevice = device)
            }
        }
        if (shouldPersist) persistCacheSnapshot()
    }

    fun updateDeviceStatus(online: Boolean, ledOn: Boolean = false) {
        _state.update {
            it.copy(
                deviceOnline = online,
                deviceLedOn = ledOn,
                bleWifiError = if (online) null else it.bleWifiError
            )
        }
    }

    fun updateBleWifiError(message: String?) {
        _state.update { it.copy(bleWifiError = message) }
    }

    fun updateFromConfigs(
        configs: List<PinConfig>,
        fullSnapshot: Boolean,
        source: String = "ESP32",
        sampleMissingKnown: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val readingsForRecorder: List<FsrSensorReading>
        val shouldRecord: Boolean
        val selectedDevice = _state.value.selectedDevice
        val acceptedConfigs = configs
            .filter { it.direction == PIN_DIRECTION_INPUT && it.mode == PIN_MODE_ANALOG }
            .sortedBy { it.pin }

        synchronized(lock) {
            if (fullSnapshot) {
                latestConfigs.clear()
                acceptedConfigs.forEach { config ->
                    latestConfigs[config.sensorKey()] = config.normalizedSensorConfig()
                }
                latestReadings.keys
                    .filterNot { latestConfigs.containsKey(it) }
                    .forEach { key ->
                        latestReadings.remove(key)
                        history.remove(key)
                    }
            }

            val updatedKeys = mutableSetOf<String>()
            acceptedConfigs.forEach { rawConfig ->
                val config = rawConfig.normalizedSensorConfig()
                val key = config.sensorKey()
                updatedKeys += key
                latestConfigs[key] = config
                appendReading(
                    key = key,
                    pin = config.pin,
                    name = config.displayName(),
                    value = config.value,
                    source = source,
                    now = now
                )
            }

            if (sampleMissingKnown) {
                // ESP32 的 /fsr/changes 只回变化项；这里用手机端缓存补齐每 0.5 秒历史点。
                latestReadings.values
                    .filter { reading -> reading.key !in updatedKeys && latestConfigs.containsKey(reading.key) }
                    .toList()
                    .forEach { reading ->
                        appendReading(
                            key = reading.key,
                            pin = reading.pin,
                            name = reading.name,
                            value = reading.value,
                            source = reading.source,
                            now = now
                        )
                    }
            }
            trimHistory(now)
            readingsForRecorder = latestReadings.values
                .sortedWith(compareBy<FsrSensorReading> { it.pin ?: Int.MAX_VALUE }.thenBy { it.name })
            val minimumGap = maxOf(100L, (currentSettings.sampleIntervalMs * 8L) / 10L)
            shouldRecord = readingsForRecorder.isNotEmpty() && now - lastSampleRecordedAt >= minimumGap
            if (shouldRecord) {
                lastSampleRecordedAt = now
            }
        }
        if (shouldRecord) {
            recorder?.record(selectedDevice, readingsForRecorder, now)
            refreshDatabaseStats()
        }
        publishState()
        persistCacheSnapshot()
    }

    fun acceptExternalSensorPayload(values: Map<String, Int>) {
        val now = System.currentTimeMillis()
        val readingsForRecorder: List<FsrSensorReading>
        val shouldRecord: Boolean
        val selectedDevice = _state.value.selectedDevice
        synchronized(lock) {
            values.toSortedMap().forEach { (rawName, rawValue) ->
                val key = "push_${rawName.trim()}"
                appendReading(
                    key = key,
                    pin = null,
                    name = labelForPushedKey(rawName.trim()),
                    value = rawValue,
                    source = "推送",
                    now = now
                )
            }
            trimHistory(now)
            readingsForRecorder = latestReadings.values
                .sortedWith(compareBy<FsrSensorReading> { it.pin ?: Int.MAX_VALUE }.thenBy { it.name })
            val minimumGap = maxOf(100L, (currentSettings.sampleIntervalMs * 8L) / 10L)
            shouldRecord = readingsForRecorder.isNotEmpty() && now - lastSampleRecordedAt >= minimumGap
            if (shouldRecord) {
                lastSampleRecordedAt = now
            }
        }
        if (shouldRecord) {
            recorder?.record(selectedDevice, readingsForRecorder, now)
            refreshDatabaseStats()
        }
        publishState()
        persistCacheSnapshot()
    }

    fun buildMcpSnapshot(): FsrMcpSnapshot {
        val state = _state.value
        val now = System.currentTimeMillis()
        return FsrMcpSnapshot(
            deviceName = state.selectedDevice?.name,
            deviceIp = state.selectedDevice?.ipAddress,
            deviceOnline = state.deviceOnline,
            updatedAtMillis = now,
            sensors = state.sensorReadings.map { reading -> reading.toMcpSensor(now) }
        )
    }

    fun queryHistory(
        names: Set<String>,
        fromMs: Long?,
        toMs: Long?,
        lastMs: Long?,
        intervalMs: Int?,
        compressionTolerance: Int = DEFAULT_COMPRESSION_TOLERANCE
    ): FsrHistoryResult {
        val now = System.currentTimeMillis()
        val windowMs = currentSettings.historyWindowMs
        val safeTo = (toMs ?: now).coerceAtMost(now)
        val safeFrom = when {
            fromMs != null -> fromMs
            lastMs != null -> safeTo - lastMs
            else -> safeTo - windowMs
        }.coerceAtLeast(safeTo - windowMs)
        val defaultInterval = currentSettings.sampleIntervalMs.toInt().coerceAtLeast(100)
        val safeInterval = (intervalMs ?: defaultInterval).coerceAtLeast(100)

        val series = synchronized(lock) {
            history.mapNotNull { (key, points) ->
                val reading = latestReadings[key]
                val config = latestConfigs[key]
                val name = reading?.name ?: config?.displayName() ?: key
                if (!matchesNames(names, key, name, config?.pin)) return@mapNotNull null

                val ranged = points
                    .filter { it.t in safeFrom..safeTo }
                    .downSample(safeFrom, safeInterval)
                FsrHistorySeries(
                    name = name,
                    key = key,
                    pin = config?.pin ?: reading?.pin,
                    source = reading?.source ?: "ESP32",
                    samplingIntervalMs = safeInterval,
                    data = ranged,
                    compressed = ranged.compress(compressionTolerance)
                )
            }
        }

        return FsrHistoryResult(
            nowMs = now,
            fromMs = safeFrom,
            toMs = safeTo,
            series = series
        )
    }

    fun queryChanges(
        cursor: Long?,
        names: Set<String>,
        minDelta: Int?
    ): FsrChangesResult {
        val safeMinDelta = (minDelta ?: DEFAULT_CHANGE_DELTA).coerceAtLeast(0)
        val startCursor = cursor ?: 0L
        val result = synchronized(lock) {
            val changes = changeEvents
                .filter { it.sequence > startCursor }
                .filter { it.absoluteDelta >= safeMinDelta }
                .filter { matchesNames(names, it.key, it.name, it.pin) }
            val latestCursor = changeEvents.lastOrNull()?.sequence ?: startCursor
            FsrChangesResult(
                cursor = startCursor,
                nextCursor = latestCursor,
                minDelta = safeMinDelta,
                changes = changes
            )
        }
        return result
    }

    fun querySessions(limit: Int, sinceMs: Long?): List<FsrSessionSummary> {
        return database?.querySessions(limit = limit, sinceMs = sinceMs).orEmpty()
    }

    fun queryEvents(
        fromMs: Long,
        toMs: Long,
        names: Set<String>,
        type: String?,
        sessionId: String?,
        limit: Int
    ): List<FsrLocalEvent> {
        return database?.queryEvents(
            fromMs = fromMs,
            toMs = toMs,
            names = names,
            type = type,
            sessionId = sessionId,
            limit = limit
        ).orEmpty()
    }

    fun queryWindow(
        fromMs: Long,
        toMs: Long,
        limit: Int
    ): FsrWindowResult {
        recorder?.flushCurrentMinute()
        return database?.queryMinuteWindow(fromMs = fromMs, toMs = toMs, limit = limit)
            ?: FsrWindowResult(fromMs, toMs, "minute", listOf("dt", "summary", "n"), emptyList())
    }

    fun exportDatabase(context: Context): File? {
        val file = database?.exportToJson(context.applicationContext)
        refreshDatabaseStats(force = true)
        return file
    }

    fun buildSuggestedExportFileName(): String {
        return database?.buildSuggestedExportFileName() ?: "fsr-export.json"
    }

    fun exportDatabaseToUri(context: Context, uri: Uri): Boolean {
        val target = database ?: return false
        target.exportToUri(context.applicationContext, uri)
        refreshDatabaseStats(force = true)
        return true
    }

    fun clearDatabase(): String? {
        val target = database ?: return null
        val nextDir = target.clearAllAndRotateArchive()
        synchronized(lock) {
            history.clear()
            chartHistory.clear()
            changeEvents.clear()
            latestReadings.clear()
            nextSequence = 1L
            lastSampleRecordedAt = 0L
            chartStartedAt = 0L
            recorder?.resetRuntime()
        }
        persistCacheSnapshot()
        refreshDatabaseStats(force = true)
        publishState()
        return nextDir.absolutePath
    }

    fun flushRecorder() {
        recorder?.flushCurrentMinute()
        refreshDatabaseStats(force = true)
    }

    private fun appendReading(
        key: String,
        pin: Int?,
        name: String,
        value: Int,
        source: String,
        now: Long
    ) {
        val safeValue = value.coerceIn(0, FSR_ANALOG_MAX_VALUE)
        val previous = latestReadings[key]?.value ?: safeValue
        val delta = safeValue - previous
        val normalized = safeValue.toFloat() / FSR_ANALOG_MAX_VALUE.toFloat()
        val reading = FsrSensorReading(
            key = key,
            pin = pin,
            name = name,
            value = safeValue,
            previousValue = previous,
            delta = delta,
            normalized = normalized,
            updatedAtMillis = now,
            source = source
        )
        latestReadings[key] = reading

        val point = FsrHistoryPoint(
            t = now,
            value = safeValue,
            previousValue = previous,
            delta = delta,
            percent = (normalized * 100f).roundToInt().coerceIn(0, 100)
        )
        history.getOrPut(key) { ArrayDeque() }.addLast(point)
        appendChartPoint(key = key, now = now, value = safeValue)

        if (delta != 0) {
            changeEvents.addLast(
                FsrChangeEvent(
                    sequence = nextSequence++,
                    t = now,
                    name = name,
                    key = key,
                    pin = pin,
                    value = safeValue,
                    previousValue = previous,
                    delta = delta,
                    absoluteDelta = abs(delta),
                    percent = point.percent,
                    source = source
                )
            )
            while (changeEvents.size > MAX_CHANGE_EVENTS) {
                changeEvents.removeFirst()
            }
        }
    }

    private fun publishState() {
        val configs: List<PinConfig>
        val readings: List<FsrSensorReading>
        val uiHistory: Map<String, List<PinHistoryPoint>>
        synchronized(lock) {
            configs = latestConfigs.values.sortedBy { it.pin }
            readings = latestReadings.values
                .sortedWith(compareBy<FsrSensorReading> { it.pin ?: Int.MAX_VALUE }.thenBy { it.name })
            uiHistory = chartHistory.mapValues { (_, points) -> points.toList() }
        }
        _state.update {
            it.copy(
                sensorConfigs = configs,
                sensorReadings = readings,
                sensorHistory = uiHistory
            )
        }
    }

    private fun trimHistory(now: Long) {
        val oldest = now - currentSettings.historyWindowMs
        history.values.forEach { points ->
            while (points.firstOrNull()?.t?.let { it < oldest } == true) {
                points.removeFirst()
            }
        }
        while (changeEvents.firstOrNull()?.t?.let { it < oldest } == true) {
            changeEvents.removeFirst()
        }
    }

    private fun appendChartPoint(
        key: String,
        now: Long,
        value: Int
    ) {
        if (chartStartedAt == 0L) {
            chartStartedAt = now
        }
        val elapsedSecond = ((now - chartStartedAt) / 1000L).toInt().coerceAtLeast(0)
        val points = chartHistory.getOrPut(key) { ArrayDeque() }
        if (points.lastOrNull()?.second == elapsedSecond) {
            points.removeLast()
        }
        points.addLast(PinHistoryPoint(second = elapsedSecond, value = value))
        val oldestSecond = (elapsedSecond - CHART_WINDOW_SECONDS + 1).coerceAtLeast(0)
        while (points.firstOrNull()?.second?.let { it < oldestSecond } == true) {
            points.removeFirst()
        }
    }

    private fun loadCacheLocked(file: File, now: Long) {
        if (!file.exists()) return
        val loaded = runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), PersistedFsrCache::class.java)
        }.getOrNull() ?: return

        latestConfigs.clear()
        latestReadings.clear()
        history.clear()
        chartHistory.clear()
        changeEvents.clear()

        val oldest = now - currentSettings.historyWindowMs
        loaded.latestConfigs.orEmpty()
            .filter { it.direction == PIN_DIRECTION_INPUT && it.mode == PIN_MODE_ANALOG }
            .forEach { config ->
                latestConfigs[config.sensorKey()] = config.normalizedSensorConfig()
            }

        loaded.latestReadings.orEmpty()
            .filter { it.updatedAtMillis >= oldest }
            .forEach { reading ->
                latestReadings[reading.key] = reading
            }

        loaded.history.orEmpty().forEach { (key, points) ->
            val trimmed = points
                .filter { it.t >= oldest }
                .sortedBy { it.t }
            if (trimmed.isNotEmpty()) {
                history[key] = ArrayDeque<FsrHistoryPoint>().apply { addAll(trimmed) }
            }
        }

        loaded.changeEvents.orEmpty()
            .filter { it.t >= oldest }
            .sortedBy { it.sequence }
            .forEach { changeEvents.addLast(it) }

        val maxLoadedSequence = changeEvents.maxOfOrNull { it.sequence } ?: 0L
        nextSequence = maxOf(loaded.nextSequence ?: 1L, maxLoadedSequence + 1L)
        trimHistory(now)
    }

    private fun persistCacheSnapshot() {
        val file = cacheFile ?: return
        val snapshot = synchronized(lock) {
            PersistedFsrCache(
                savedAtMillis = System.currentTimeMillis(),
                latestConfigs = latestConfigs.values.toList(),
                latestReadings = latestReadings.values.toList(),
                history = history.mapValues { (_, points) -> points.toList() },
                changeEvents = changeEvents.toList(),
                nextSequence = nextSequence
            )
        }
        persistExecutor.execute {
            runCatching {
                val parent = file.parentFile ?: return@runCatching
                parent.mkdirs()
                val tmp = File(parent, "$CACHE_FILE_NAME.tmp")
                tmp.writeText(gson.toJson(snapshot), Charsets.UTF_8)
                if (tmp.renameTo(file).not()) {
                    file.writeText(gson.toJson(snapshot), Charsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    private fun List<FsrHistoryPoint>.downSample(fromMs: Long, intervalMs: Int): List<FsrHistoryPoint> {
        if (intervalMs <= currentSettings.sampleIntervalMs) return this
        return groupBy { ((it.t - fromMs).coerceAtLeast(0L) / intervalMs) }
            .values
            .mapNotNull { it.lastOrNull() }
            .sortedBy { it.t }
    }

    private fun refreshDatabaseStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsPublishedAt < DATABASE_STATS_REFRESH_MS) return
        lastStatsPublishedAt = now
        val stats = database?.getStats() ?: FsrDatabaseStats()
        _state.update { it.copy(databaseStats = stats) }
    }

    private fun List<FsrHistoryPoint>.compress(tolerance: Int): List<FsrHistorySegment> {
        if (isEmpty()) return emptyList()
        val segments = mutableListOf<FsrHistorySegment>()
        var start = first()
        var end = first()
        var minValue = first().value
        var maxValue = first().value
        var samples = 1

        drop(1).forEach { point ->
            if (abs(point.value - end.value) <= tolerance && abs(point.value - start.value) <= tolerance) {
                end = point
                minValue = minOf(minValue, point.value)
                maxValue = maxOf(maxValue, point.value)
                samples++
            } else {
                segments.add(start.toSegment(end, minValue, maxValue, samples))
                start = point
                end = point
                minValue = point.value
                maxValue = point.value
                samples = 1
            }
        }
        segments.add(start.toSegment(end, minValue, maxValue, samples))
        return segments
    }

    private fun FsrHistoryPoint.toSegment(
        end: FsrHistoryPoint,
        minValue: Int,
        maxValue: Int,
        samples: Int
    ): FsrHistorySegment {
        return FsrHistorySegment(
            fromMs = t,
            toMs = end.t,
            value = end.value,
            minValue = minValue,
            maxValue = maxValue,
            percent = end.percent,
            samples = samples
        )
    }

    private fun PinConfig.normalizedSensorConfig(): PinConfig {
        return copy(
            id = id.ifBlank { sensorKey() },
            value = value.coerceIn(0, FSR_ANALOG_MAX_VALUE),
            label = displayName()
        )
    }

    private fun PinConfig.sensorKey(): String {
        return "gpio_$pin"
    }

    private fun PinConfig.displayName(): String {
        return label.ifBlank { "GPIO$pin" }
    }

    private fun matchesNames(names: Set<String>, key: String, name: String, pin: Int?): Boolean {
        if (names.isEmpty()) return true
        val candidates = buildSet {
            add(key.lowercase())
            add(name.lowercase())
            pin?.let {
                add("gpio$it")
                add("gpio_$it")
                add(it.toString())
            }
        }
        return names.any { it.lowercase() in candidates }
    }

    private fun labelForPushedKey(key: String): String {
        return when (key.lowercase()) {
            "el" -> "左耳"
            "er" -> "右耳"
            "fl" -> "左前肢"
            "fr" -> "右前肢"
            "bl" -> "左后肢"
            "br" -> "右后肢"
            "hd" -> "头部"
            "bd" -> "身体"
            else -> key
        }
    }

    private data class PersistedFsrCache(
        val savedAtMillis: Long? = null,
        val latestConfigs: List<PinConfig>? = null,
        val latestReadings: List<FsrSensorReading>? = null,
        val history: Map<String, List<FsrHistoryPoint>>? = null,
        val changeEvents: List<FsrChangeEvent>? = null,
        val nextSequence: Long? = null
    )
}
