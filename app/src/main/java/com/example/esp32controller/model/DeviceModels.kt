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
