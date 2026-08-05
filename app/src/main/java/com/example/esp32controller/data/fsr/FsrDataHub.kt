package com.example.esp32controller.data.fsr

import com.example.esp32controller.data.mcp.toMcpSensor
import com.example.esp32controller.model.DeviceUiModel
import com.example.esp32controller.model.FSR_ANALOG_MAX_VALUE
import com.example.esp32controller.model.FsrChangeEvent
import com.example.esp32controller.model.FsrChangesResult
import com.example.esp32controller.model.FsrHistoryPoint
import com.example.esp32controller.model.FsrHistoryResult
import com.example.esp32controller.model.FsrHistorySegment
import com.example.esp32controller.model.FsrHistorySeries
import com.example.esp32controller.model.FsrMcpSnapshot
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.McpServerState
import com.example.esp32controller.model.PIN_DIRECTION_INPUT
import com.example.esp32controller.model.PIN_MODE_ANALOG
import com.example.esp32controller.model.PinConfig
import com.example.esp32controller.model.PinHistoryPoint
import com.example.esp32controller.model.StoredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HISTORY_WINDOW_MS = 60_000L
private const val DEFAULT_SAMPLING_INTERVAL_MS = 500
private const val DEFAULT_CHANGE_DELTA = 8
private const val DEFAULT_COMPRESSION_TOLERANCE = 15
private const val MAX_CHANGE_EVENTS = 2400

data class FsrBridgeState(
    val selectedDevice: StoredDevice? = null,
    val deviceOnline: Boolean = false,
    val deviceLedOn: Boolean = false,
    val sensorConfigs: List<PinConfig> = emptyList(),
    val sensorReadings: List<FsrSensorReading> = emptyList(),
    val sensorHistory: Map<String, List<PinHistoryPoint>> = emptyMap(),
    val mcpState: McpServerState = McpServerState(),
    val bleWifiError: String? = null
)

object FsrDataHub {
    private val lock = Any()
    private val latestReadings = linkedMapOf<String, FsrSensorReading>()
    private val latestConfigs = linkedMapOf<String, PinConfig>()
    private val history = linkedMapOf<String, ArrayDeque<FsrHistoryPoint>>()
    private val changeEvents = ArrayDeque<FsrChangeEvent>()
    private var nextSequence = 1L

    private val _state = MutableStateFlow(FsrBridgeState())
    val state: StateFlow<FsrBridgeState> = _state.asStateFlow()

    fun updateMcpState(mcpState: McpServerState) {
        _state.update { it.copy(mcpState = mcpState) }
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
        _state.update {
            if (device == null) {
                synchronized(lock) {
                    latestConfigs.clear()
                    latestReadings.clear()
                    history.clear()
                    changeEvents.clear()
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
        }
        publishState()
    }

    fun acceptExternalSensorPayload(values: Map<String, Int>) {
        val now = System.currentTimeMillis()
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
        }
        publishState()
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
        val safeTo = (toMs ?: now).coerceAtMost(now)
        val safeFrom = when {
            fromMs != null -> fromMs
            lastMs != null -> safeTo - lastMs
            else -> safeTo - HISTORY_WINDOW_MS
        }.coerceAtLeast(safeTo - HISTORY_WINDOW_MS)
        val safeInterval = (intervalMs ?: DEFAULT_SAMPLING_INTERVAL_MS).coerceAtLeast(DEFAULT_SAMPLING_INTERVAL_MS)

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
        val now = System.currentTimeMillis()
        val configs: List<PinConfig>
        val readings: List<FsrSensorReading>
        val uiHistory: Map<String, List<PinHistoryPoint>>
        synchronized(lock) {
            configs = latestConfigs.values.sortedBy { it.pin }
            readings = latestReadings.values
                .sortedWith(compareBy<FsrSensorReading> { it.pin ?: Int.MAX_VALUE }.thenBy { it.name })
            uiHistory = history.mapValues { (_, points) ->
                points.map { point ->
                    PinHistoryPoint(
                        second = ((point.t - (now - HISTORY_WINDOW_MS)) / 1000L).toInt().coerceAtLeast(0),
                        value = point.value
                    )
                }
            }
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
        val oldest = now - HISTORY_WINDOW_MS
        history.values.forEach { points ->
            while (points.firstOrNull()?.t?.let { it < oldest } == true) {
                points.removeFirst()
            }
        }
        while (changeEvents.firstOrNull()?.t?.let { it < oldest } == true) {
            changeEvents.removeFirst()
        }
    }

    private fun List<FsrHistoryPoint>.downSample(fromMs: Long, intervalMs: Int): List<FsrHistoryPoint> {
        if (intervalMs <= DEFAULT_SAMPLING_INTERVAL_MS) return this
        return groupBy { ((it.t - fromMs).coerceAtLeast(0L) / intervalMs) }
            .values
            .mapNotNull { it.lastOrNull() }
            .sortedBy { it.t }
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
}
