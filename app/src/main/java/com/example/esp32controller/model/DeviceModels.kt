package com.example.esp32controller.model

import android.bluetooth.BluetoothDevice

const val DEFAULT_MDNS_HOST = "esp32.local"

const val PIN_DIRECTION_INPUT = "input"
const val PIN_DIRECTION_OUTPUT = "output"
const val PIN_MODE_DIGITAL = "digital"
const val PIN_MODE_ANALOG = "analog"
const val CONFIG_KIND_GPIO = "gpio"

const val FSR_ANALOG_MAX_VALUE = 4095
const val MCP_DEFAULT_PORT = 9333
const val DEFAULT_FSR_HISTORY_WINDOW_MS = 60_000L
const val DEFAULT_FSR_SAMPLE_INTERVAL_MS = 1_000L
const val DEFAULT_FSR_TRIGGER_THRESHOLD = 300

val FSR_SENSOR_PINS = (1..10).toList()

data class StoredDevice(
    val name: String,
    val macAddress: String,
    val ipAddress: String,
    val hostName: String = DEFAULT_MDNS_HOST,
    val wifiSsid: String? = null
)

data class BleScanDevice(
    val name: String,
    val macAddress: String,
    val device: BluetoothDevice
)

data class DeviceRuntimeState(
    val online: Boolean = false,
    val isOn: Boolean = false
)

data class DeviceUiModel(
    val name: String,
    val macAddress: String,
    val ipAddress: String,
    val online: Boolean,
    val isOn: Boolean,
    val isSelected: Boolean
)

data class PairingResult(
    val ipAddress: String,
    val wifiSsid: String? = null,
    val reason: String? = null
)

data class WifiNetworkOption(
    val ssid: String,
    val isCurrent: Boolean = false,
    val frequencyMhz: Int? = null
) {
    val isFiveG: Boolean
        get() = frequencyMhz != null && frequencyMhz in 4900..5900

    val isTwoPointFourG: Boolean
        get() = frequencyMhz != null && frequencyMhz in 2400..2500
}

data class StoredDevicesSnapshot(
    val devices: List<StoredDevice> = emptyList(),
    val selectedMacAddress: String? = null
)

data class PinCapability(
    val pin: Int = 0,
    val digitalInput: Boolean = false,
    val digitalOutput: Boolean = false,
    val analogInput: Boolean = false,
    val analogOutput: Boolean = false,
    val analogInputKind: String = "",
    val analogOutputKind: String = "",
    val note: String = ""
)

data class PinConfig(
    val id: String = "",
    val kind: String = CONFIG_KIND_GPIO,
    val pin: Int = 0,
    val direction: String = PIN_DIRECTION_INPUT,
    val mode: String = PIN_MODE_ANALOG,
    val value: Int = 0,
    val source: String = "",
    val order: Int = 0,
    val label: String = ""
)

data class PinDashboard(
    val device: String = "",
    val ip: String = "",
    val pins: List<PinCapability> = emptyList(),
    val configs: List<PinConfig> = emptyList()
)

data class PinOperationResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val config: PinConfig? = null
)

data class PinHistoryPoint(
    val second: Int,
    val value: Int
)

data class FsrSensorReading(
    val key: String,
    val pin: Int? = null,
    val name: String,
    val value: Int,
    val previousValue: Int,
    val delta: Int,
    val normalized: Float,
    val updatedAtMillis: Long,
    val source: String
) {
    val label: String
        get() = name
}

data class FsrMcpSensor(
    val key: String,
    val pin: Int?,
    val name: String,
    val value: Int,
    val previousValue: Int,
    val delta: Int,
    val absoluteDelta: Int,
    val normalized: Float,
    val percent: Int,
    val ageMillis: Long,
    val source: String
) {
    val label: String
        get() = name
}

data class FsrMcpSnapshot(
    val deviceName: String?,
    val deviceIp: String?,
    val deviceOnline: Boolean,
    val resolutionBits: Int = 12,
    val maxValue: Int = FSR_ANALOG_MAX_VALUE,
    val updatedAtMillis: Long,
    val sensors: List<FsrMcpSensor>
)

data class McpServerState(
    val enabled: Boolean = true,
    val running: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = MCP_DEFAULT_PORT,
    val error: String? = null
) {
    val url: String
        get() = "http://$host:$port/mcp"
}

data class SupabaseSettings(
    val enabled: Boolean = false,
    val projectUrl: String = "",
    val anonKey: String = "",
    val minuteDataTable: String = "fsr_minute_data",
    val sessionsTable: String = "fsr_sessions"
) {
    val configured: Boolean
        get() = projectUrl.trim().isNotBlank() && anonKey.trim().isNotBlank()
}

data class FsrBridgeSettings(
    val historyWindowMs: Long = DEFAULT_FSR_HISTORY_WINDOW_MS,
    val sampleIntervalMs: Long = DEFAULT_FSR_SAMPLE_INTERVAL_MS,
    val triggerThreshold: Int = DEFAULT_FSR_TRIGGER_THRESHOLD,
    val supabase: SupabaseSettings = SupabaseSettings()
)

data class FsrDatabaseStats(
    val sampleRows: Long = 0,
    val eventRows: Long = 0,
    val sessionRows: Long = 0,
    val minuteRows: Long = 0,
    val pendingUploads: Long = 0,
    val lastSampleAtMs: Long? = null
)

data class SupabaseSyncState(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncAtMs: Long? = null,
    val lastMessage: String? = null,
    val lastError: String? = null
)

data class FsrLocalEvent(
    val id: Long = 0,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val type: String,
    val sensors: List<String>,
    val peakValue: Int,
    val avgValue: Int,
    val summary: String
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

data class FsrSessionSummary(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val avgPressure: Float,
    val maxPressure: Int,
    val hugCount: Int,
    val pokeCount: Int,
    val pinchCount: Int,
    val strokeCount: Int,
    val pressCount: Int,
    val summary: String,
    val updatedAtMs: Long
)

data class FsrMinuteRollup(
    val localId: Long = 0,
    val remoteId: String,
    val sessionId: String,
    val minuteStartMs: Long,
    val deviceMac: String?,
    val deviceName: String?,
    val samples: Int,
    val valuesJson: String,
    val summary: String,
    val uploaded: Boolean = false
)

data class FsrWindowResult(
    val fromMs: Long,
    val toMs: Long,
    val mode: String,
    val cols: List<String>,
    val rows: List<List<Any?>>
)

data class FsrHistoryPoint(
    val t: Long,
    val value: Int,
    val previousValue: Int,
    val delta: Int,
    val percent: Int
)

data class FsrHistorySegment(
    val fromMs: Long,
    val toMs: Long,
    val value: Int,
    val minValue: Int,
    val maxValue: Int,
    val percent: Int,
    val samples: Int
)

data class FsrHistorySeries(
    val name: String,
    val key: String,
    val pin: Int?,
    val source: String,
    val samplingIntervalMs: Int,
    val data: List<FsrHistoryPoint>,
    val compressed: List<FsrHistorySegment>
)

data class FsrHistoryResult(
    val nowMs: Long,
    val fromMs: Long,
    val toMs: Long,
    val resolutionBits: Int = 12,
    val maxValue: Int = FSR_ANALOG_MAX_VALUE,
    val series: List<FsrHistorySeries>
)

data class FsrChangeEvent(
    val sequence: Long,
    val t: Long,
    val name: String,
    val key: String,
    val pin: Int?,
    val value: Int,
    val previousValue: Int,
    val delta: Int,
    val absoluteDelta: Int,
    val percent: Int,
    val source: String
)

data class FsrChangesResult(
    val cursor: Long,
    val nextCursor: Long,
    val minDelta: Int,
    val changes: List<FsrChangeEvent>
)
