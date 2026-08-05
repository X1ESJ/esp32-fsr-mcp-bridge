package com.example.esp32controller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.esp32controller.data.ble.BleProvisioningException
import com.example.esp32controller.data.ble.BleProvisioningManager
import com.example.esp32controller.data.fsr.FsrDataHub
import com.example.esp32controller.data.mcp.toMcpSensor
import com.example.esp32controller.data.mdns.MdnsResolver
import com.example.esp32controller.data.network.Esp32ApiClient
import com.example.esp32controller.data.network.Esp32ApiService
import com.example.esp32controller.data.storage.DeviceStore
import com.example.esp32controller.model.BleScanDevice
import com.example.esp32controller.model.DEFAULT_MDNS_HOST
import com.example.esp32controller.model.DeviceRuntimeState
import com.example.esp32controller.model.DeviceUiModel
import com.example.esp32controller.model.FSR_ANALOG_MAX_VALUE
import com.example.esp32controller.model.FSR_SENSOR_PINS
import com.example.esp32controller.model.FsrMcpSnapshot
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.McpServerState
import com.example.esp32controller.model.PIN_DIRECTION_INPUT
import com.example.esp32controller.model.PIN_MODE_ANALOG
import com.example.esp32controller.model.PairingResult
import com.example.esp32controller.model.PinCapability
import com.example.esp32controller.model.PinConfig
import com.example.esp32controller.model.PinDashboard
import com.example.esp32controller.model.PinHistoryPoint
import com.example.esp32controller.model.StoredDevice
import com.example.esp32controller.model.WifiNetworkOption
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val SENSOR_POLL_FAST_MS = 300L
private const val SENSOR_POLL_IDLE_MS = 1000L

private val fallbackPinCapabilities = FSR_SENSOR_PINS.map { pin ->
    PinCapability(
        pin = pin,
        analogInput = true,
        analogInputKind = "ADC1",
        note = "FSR402 推荐接到 ESP32-S3 的 ADC1 引脚，App 按 12 位 0-4095 读取。"
    )
}

data class MainUiState(
    val devices: List<DeviceUiModel> = emptyList(),
    val selectedDeviceMac: String? = null,
    val pairingVisible: Boolean = false,
    val pairingStep: Int = 0,
    val pairingMaxStep: Int = 0,
    val bleDevices: List<BleScanDevice> = emptyList(),
    val selectedBleDevice: BleScanDevice? = null,
    val currentWifiSsid: String = "",
    val selectedWifiSsid: String = "",
    val availableWifiNetworks: List<WifiNetworkOption> = emptyList(),
    val pairingBusy: Boolean = false,
    val pairingMessage: String? = null,
    val pairingError: String? = null,
    val controlError: String? = null,
    val mdnsScanning: Boolean = false,
    val showRetryButton: Boolean = false,
    val sensorPanelVisible: Boolean = false,
    val pinCapabilities: List<PinCapability> = fallbackPinCapabilities,
    val sensorConfigs: List<PinConfig> = emptyList(),
    val sensorReadings: List<FsrSensorReading> = emptyList(),
    val sensorHistory: Map<String, List<PinHistoryPoint>> = emptyMap(),
    val pinBusy: Boolean = false,
    val mcpState: McpServerState = McpServerState(),
    val bleWifiError: String? = null,
    val selectedWifiFrequencyMhz: Int? = null,
    val routerPingOk: Boolean? = null,
    val routerPingInProgress: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val gson = Gson()
    private val deviceStore = DeviceStore(appContext, gson)
    private val bleProvisioningManager = BleProvisioningManager(appContext)
    private val apiClient = Esp32ApiClient()
    private val mdnsResolver = MdnsResolver(appContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var storedDevices: List<StoredDevice> = emptyList()
    private val runtimeState = mutableMapOf<String, DeviceRuntimeState>()
    private val previousSensorValues = mutableMapOf<String, Int>()
    private val polledReadings = linkedMapOf<String, FsrSensorReading>()
    private val pushedReadings = linkedMapOf<String, FsrSensorReading>()
    private val pinHistoryStartedAt = System.currentTimeMillis()

    private var observeStoreJob: Job? = null
    private var bleScanJob: Job? = null
    private var provisionJob: Job? = null
    private var pollJob: Job? = null
    private var mdnsJob: Job? = null
    private var wifiPingJob: Job? = null
    private var lastWifiPingKey: String? = null

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                updateWifiContextFromCache()
            }
        }
    }

    init {
        registerWifiScanReceiver()
        observeStoredDevices()
        observeBridgeHub()
        refreshWifiContext()
        startMdnsWindow()
    }

    fun openPairingFlow() {
        refreshWifiContext()
        _uiState.update {
            it.copy(
                pairingVisible = true,
                pairingStep = 0,
                pairingMaxStep = 0,
                pairingBusy = false,
                pairingError = null,
                pairingMessage = "请选择进入配网模式的 ESP32",
                bleDevices = emptyList(),
                selectedBleDevice = null
            )
        }
        startBleScan()
    }

    fun closePairingFlow() {
        bleScanJob?.cancel()
        provisionJob?.cancel()
        _uiState.update {
            it.copy(
                pairingVisible = false,
                pairingBusy = false,
                pairingError = null,
                pairingMessage = null,
                bleDevices = emptyList(),
                selectedBleDevice = null
            )
        }
    }

    fun selectBleDevice(device: BleScanDevice) {
        _uiState.update { it.copy(selectedBleDevice = device, pairingError = null) }
    }

    fun selectWifiNetwork(ssid: String) {
        _uiState.update { it.copy(selectedWifiSsid = ssid, pairingError = null) }
        updateSelectedWifiCompatibility(ssid, _uiState.value.availableWifiNetworks)
    }

    fun refreshWifiContext() {
        runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.startScan()
        }
        updateWifiContextFromCache()
    }

    fun goToWifiStep() {
        val selectedDevice = _uiState.value.selectedBleDevice
        if (selectedDevice == null) {
            showPairingError("请先选择一个 BLE 设备", 0)
            return
        }

        refreshWifiContext()
        bleScanJob?.cancel()
        _uiState.update {
            it.copy(
                pairingStep = 1,
                pairingMaxStep = maxOf(it.pairingMaxStep, 1),
                pairingError = null,
                pairingMessage = "确认 WiFi 网络并输入密码"
            )
        }
    }

    fun backFromStep(currentStep: Int) {
        when (currentStep) {
            0 -> closePairingFlow()
            1 -> {
                _uiState.update {
                    it.copy(
                        pairingStep = 0,
                        pairingBusy = false,
                        pairingError = null,
                        pairingMessage = "请选择进入配网模式的 ESP32"
                    )
                }
                startBleScan()
            }
            2 -> {
                provisionJob?.cancel()
                _uiState.update {
                    it.copy(
                        pairingStep = 1,
                        pairingBusy = false,
                        pairingError = null,
                        pairingMessage = "请重新确认 WiFi 网络和密码"
                    )
                }
            }
        }
    }

    fun confirmWifiPassword(password: String) {
        val selectedDevice = _uiState.value.selectedBleDevice
        val ssid = _uiState.value.selectedWifiSsid.ifBlank { _uiState.value.currentWifiSsid }

        if (selectedDevice == null) {
            showPairingError("BLE 设备未选择，请重新扫描", 0)
            return
        }
        if (ssid.isBlank()) {
            showPairingError("没有可用 WiFi，请刷新网络列表", 1)
            return
        }
        if (password.isBlank()) {
            showPairingError("请输入 WiFi 密码", 1)
            return
        }

        provisionJob?.cancel()
        _uiState.update {
            it.copy(
                pairingStep = 2,
                pairingMaxStep = maxOf(it.pairingMaxStep, 2),
                pairingBusy = true,
                pairingError = null,
                pairingMessage = "正在发送 WiFi 信息..."
            )
        }

        provisionJob = viewModelScope.launch {
            try {
                val result = bleProvisioningManager.provision(selectedDevice.device, ssid, password)
                completeProvisioning(selectedDevice, result.copy(wifiSsid = result.wifiSsid ?: ssid))
            } catch (exception: BleProvisioningException) {
                val fallbackIp = if (exception.returnStep > 0) {
                    tryResolveAfterBleProblem()
                } else {
                    null
                }
                if (!fallbackIp.isNullOrBlank()) {
                    completeProvisioning(
                        selectedDevice,
                        PairingResult(ipAddress = fallbackIp, wifiSsid = ssid)
                    )
                    return@launch
                }
                val bleStatusMessage = if (exception.returnStep > 0) {
                    readPairingBleStatus(selectedDevice.macAddress)
                } else {
                    null
                }

                showPairingError(
                    message = bleStatusMessage ?: exception.message ?: "ESP32 配网失败",
                    step = exception.returnStep
                )
                if (exception.returnStep == 0) startBleScan()
            } catch (exception: Exception) {
                showPairingError(exception.message ?: "配网时发生未知错误", 1)
            }
        }
    }

    fun selectDevice(macAddress: String) {
        _uiState.update {
            it.copy(
                selectedDeviceMac = macAddress,
                controlError = null,
                sensorConfigs = emptyList(),
                sensorHistory = emptyMap()
            )
        }
        clearPolledReadings()
        viewModelScope.launch { deviceStore.updateSelectedDevice(macAddress) }
        refreshSensorDashboard(showErrors = false)
        startStatusPolling()
    }

    fun deleteStoredDevice(macAddress: String) {
        viewModelScope.launch {
            val deletingSelected = _uiState.value.selectedDeviceMac == macAddress
            runtimeState.remove(macAddress)
            if (deletingSelected) {
                _uiState.update {
                    it.copy(
                        selectedDeviceMac = null,
                        sensorPanelVisible = false,
                        sensorConfigs = emptyList(),
                        sensorHistory = emptyMap()
                    )
                }
                clearPolledReadings()
            }
            deviceStore.deleteDevice(macAddress)
            rebuildUiState()
        }
    }

    fun openSensorPanel() {
        if (getSelectedStoredDevice() == null) {
            _uiState.update { it.copy(controlError = "请先选择一个设备") }
            return
        }
        _uiState.update { it.copy(sensorPanelVisible = true, controlError = null, pinBusy = true) }
        refreshSensorDashboard(showErrors = true)
        startStatusPolling()
    }

    fun closeSensorPanel() {
        _uiState.update { it.copy(sensorPanelVisible = false) }
    }

    fun saveFsrSensor(pin: Int, label: String) {
        val selectedDevice = getSelectedStoredDevice() ?: run {
            _uiState.update { it.copy(controlError = "请先选择一个设备") }
            return
        }
        if (pin !in FSR_SENSOR_PINS) {
            _uiState.update { it.copy(controlError = "FSR402 传感器只能选择 GPIO1-GPIO10") }
            return
        }
        val safeLabel = label.trim()
        if (safeLabel.isBlank()) {
            _uiState.update { it.copy(controlError = "传感器名称不能为空") }
            return
        }
        if (_uiState.value.sensorConfigs.any { it.pin != pin && it.label.equals(safeLabel, ignoreCase = true) }) {
            _uiState.update { it.copy(controlError = "传感器名称不能重复") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pinBusy = true, controlError = null) }
            try {
                val service = apiClient.create(selectedDevice.ipAddress)
                val response = service.configurePin(
                    pin = pin,
                    direction = PIN_DIRECTION_INPUT,
                    mode = PIN_MODE_ANALOG,
                    value = 0,
                    label = safeLabel
                )
                if (!response.ok) {
                    throw IllegalStateException(response.error ?: "传感器配置失败")
                }
                loadSensorDashboardFromService(service)
                refreshSelectedDeviceStatus(showErrors = false)
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        pinBusy = false,
                        controlError = "保存传感器失败：${exception.message ?: "网络超时"}"
                    )
                }
                markDeviceOffline(selectedDevice.macAddress)
                startMdnsWindow()
            }
        }
    }

    fun deleteSensor(config: PinConfig) {
        val selectedDevice = getSelectedStoredDevice() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pinBusy = true, controlError = null) }
            try {
                val response = apiClient.create(selectedDevice.ipAddress).deletePinConfig(config.pin)
                if (!response.ok) {
                    throw IllegalStateException(response.error ?: "删除传感器失败")
                }
                polledReadings.remove(config.sensorKey())
                previousSensorValues.remove(config.sensorKey())
                loadSensorDashboardFromService(apiClient.create(selectedDevice.ipAddress))
                _uiState.update { state ->
                    state.copy(sensorHistory = state.sensorHistory - config.sensorKey())
                }
                publishSensorReadings()
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        pinBusy = false,
                        controlError = "删除传感器失败：${exception.message ?: "网络超时"}"
                    )
                }
            }
        }
    }

    fun retryMdns() {
        startMdnsWindow(forceRestart = true)
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(pairingError = null, controlError = null) }
    }

    private fun observeBridgeHub() {
        viewModelScope.launch {
            FsrDataHub.state.collect { bridgeState ->
                bridgeState.selectedDevice?.let { device ->
                    runtimeState[device.macAddress] = DeviceRuntimeState(
                        online = bridgeState.deviceOnline,
                        isOn = bridgeState.deviceLedOn
                    )
                    rebuildUiState()
                }
                _uiState.update {
                    it.copy(
                        sensorConfigs = bridgeState.sensorConfigs,
                        sensorReadings = bridgeState.sensorReadings,
                        sensorHistory = bridgeState.sensorHistory,
                        mcpState = bridgeState.mcpState,
                        bleWifiError = bridgeState.bleWifiError,
                        pinBusy = false
                    )
                }
            }
        }
    }

    private fun refreshSensorDashboard(showErrors: Boolean) {
        val selectedDevice = getSelectedStoredDevice() ?: return
        viewModelScope.launch {
            try {
                loadSensorDashboardFromService(apiClient.create(selectedDevice.ipAddress))
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        pinBusy = false,
                        controlError = if (showErrors) {
                            "读取传感器配置失败：${exception.message ?: "网络超时"}"
                        } else {
                            it.controlError
                        }
                    )
                }
                markDeviceOffline(selectedDevice.macAddress)
                startMdnsWindow()
            }
        }
    }

    private suspend fun loadSensorDashboardFromService(service: Esp32ApiService) {
        val dashboard = service.getPins()
        applySensorDashboard(dashboard)
    }

    private fun applySensorDashboard(dashboard: PinDashboard) {
        val capabilities = mergePinCapabilities(dashboard.pins)
        val configs = dashboard.configs.filterFsrConfigs()
        FsrDataHub.updateFromConfigs(configs, fullSnapshot = true)
        applySensorConfigs(configs, capabilities)
    }

    private fun applySensorSnapshot(configs: List<PinConfig>) {
        val fsrConfigs = configs.filterFsrConfigs()
        FsrDataHub.updateFromConfigs(fsrConfigs, fullSnapshot = false)
        applySensorConfigs(fsrConfigs, nextCapabilities = null)
    }

    private fun applySensorConfigs(
        configs: List<PinConfig>,
        nextCapabilities: List<PinCapability>?
    ) {
        val now = System.currentTimeMillis()
        val elapsedSecond = ((now - pinHistoryStartedAt) / 1000L).toInt()
        val sortedConfigs = configs.sortedBy { it.pin }
        val configuredKeys = sortedConfigs.map { it.sensorKey() }.toSet()

        sortedConfigs.forEach { config ->
            val key = config.sensorKey()
            val safeValue = config.value.coerceIn(0, FSR_ANALOG_MAX_VALUE)
            val previous = previousSensorValues[key] ?: safeValue
            previousSensorValues[key] = safeValue
            polledReadings[key] = FsrSensorReading(
                key = key,
                pin = config.pin,
                name = config.label.ifBlank { "GPIO${config.pin}" },
                value = safeValue,
                previousValue = previous,
                delta = safeValue - previous,
                normalized = safeValue.toFloat() / FSR_ANALOG_MAX_VALUE.toFloat(),
                updatedAtMillis = now,
                source = "ESP32"
            )
        }

        polledReadings.keys
            .filterNot { it in configuredKeys }
            .forEach { staleKey -> polledReadings.remove(staleKey) }

        _uiState.update { state ->
            val nextHistory = state.sensorHistory.toMutableMap()
            polledReadings.values.forEach { reading ->
                val slot = elapsedSecond % 60
                val kept = nextHistory[reading.key].orEmpty()
                    .filter { elapsedSecond - it.second in 0..59 }
                    .filterNot { it.second % 60 == slot }
                    .toMutableList()
                kept.add(PinHistoryPoint(elapsedSecond, reading.value))
                nextHistory[reading.key] = kept.sortedBy { it.second }
            }

            state.copy(
                pinCapabilities = nextCapabilities ?: state.pinCapabilities,
                sensorConfigs = sortedConfigs,
                sensorHistory = nextHistory.filterKeys { key ->
                    key in configuredKeys || key in pushedReadings.keys
                },
                pinBusy = false,
                controlError = null
            )
        }
        publishSensorReadings()
    }

    private fun acceptExternalSensorPayload(values: Map<String, Int>) {
        val now = System.currentTimeMillis()
        val elapsedSecond = ((now - pinHistoryStartedAt) / 1000L).toInt()
        values.toSortedMap().forEach { (rawKey, rawValue) ->
            val key = "push_${rawKey.trim()}"
            val safeValue = rawValue.coerceIn(0, FSR_ANALOG_MAX_VALUE)
            val previous = previousSensorValues[key] ?: safeValue
            previousSensorValues[key] = safeValue
            pushedReadings[key] = FsrSensorReading(
                key = key,
                pin = null,
                name = labelForPushedKey(rawKey),
                value = safeValue,
                previousValue = previous,
                delta = safeValue - previous,
                normalized = safeValue.toFloat() / FSR_ANALOG_MAX_VALUE.toFloat(),
                updatedAtMillis = now,
                source = "推送"
            )

            _uiState.update { state ->
                val nextHistory = state.sensorHistory.toMutableMap()
                val slot = elapsedSecond % 60
                val kept = nextHistory[key].orEmpty()
                    .filter { elapsedSecond - it.second in 0..59 }
                    .filterNot { it.second % 60 == slot }
                    .toMutableList()
                kept.add(PinHistoryPoint(elapsedSecond, safeValue))
                nextHistory[key] = kept.sortedBy { it.second }
                state.copy(sensorHistory = nextHistory)
            }
        }
        publishSensorReadings()
    }

    private fun publishSensorReadings() {
        val allReadings = (polledReadings.values + pushedReadings.values)
            .sortedWith(compareBy<FsrSensorReading> { it.pin ?: Int.MAX_VALUE }.thenBy { it.key })
        _uiState.update { it.copy(sensorReadings = allReadings) }
    }

    private fun buildMcpSnapshot(): FsrMcpSnapshot {
        val state = _uiState.value
        val selectedDevice = state.devices.firstOrNull { it.isSelected }
        val now = System.currentTimeMillis()
        return FsrMcpSnapshot(
            deviceName = selectedDevice?.name,
            deviceIp = selectedDevice?.ipAddress,
            deviceOnline = selectedDevice?.online == true,
            updatedAtMillis = now,
            sensors = state.sensorReadings.map { it.toMcpSensor(now) }
        )
    }

    private fun mergePinCapabilities(devicePins: List<PinCapability>): List<PinCapability> {
        val byPin = devicePins.associateBy { it.pin }
        return fallbackPinCapabilities.map { fallback ->
            val deviceCapability = byPin[fallback.pin]
            fallback.copy(
                analogInput = deviceCapability?.analogInput ?: true,
                analogInputKind = deviceCapability?.analogInputKind?.ifBlank { "ADC1" } ?: "ADC1",
                note = fallback.note
            )
        }
    }

    private fun List<PinConfig>.filterFsrConfigs(): List<PinConfig> {
        return filter {
            it.pin in FSR_SENSOR_PINS &&
                it.direction == PIN_DIRECTION_INPUT &&
                it.mode == PIN_MODE_ANALOG
        }
    }

    private fun clearPolledReadings() {
        polledReadings.clear()
        publishSensorReadings()
    }

    private suspend fun tryResolveAfterBleProblem(): String? {
        _uiState.update {
            it.copy(
                pairingStep = 2,
                pairingBusy = true,
                pairingMessage = "BLE 回包未确认，正在通过 mDNS 查找设备..."
            )
        }
        return mdnsResolver.resolveEsp32Host(timeoutMillis = 15_000L)
    }

    private suspend fun readPairingBleStatus(macAddress: String): String? {
        _uiState.update {
            it.copy(
                pairingStep = 2,
                pairingBusy = true,
                pairingMessage = "正在读取 ESP32 WiFi 失败原因..."
            )
        }
        return withTimeoutOrNull(12_000L) {
            bleProvisioningManager.scanStatusMessages(macAddress)
                .catch { }
                .firstOrNull()
        }
    }

    private suspend fun completeProvisioning(selectedDevice: BleScanDevice, result: PairingResult) {
        val storedDevice = StoredDevice(
            name = selectedDevice.name,
            macAddress = selectedDevice.macAddress,
            ipAddress = result.ipAddress,
            hostName = DEFAULT_MDNS_HOST,
            wifiSsid = result.wifiSsid
        )

        deviceStore.upsertDevice(storedDevice, selectAfterSave = true)
        val reachable = verifyDeviceReachable(result.ipAddress)
        runtimeState[selectedDevice.macAddress] = DeviceRuntimeState(online = reachable)
        if (!reachable) {
            FsrDataHub.updateBleWifiError(buildNetworkMismatchMessage(result.wifiSsid))
        } else {
            FsrDataHub.updateBleWifiError(null)
        }

        _uiState.update {
            it.copy(
                pairingStep = 3,
                pairingMaxStep = 3,
                pairingBusy = false,
                pairingMessage = "设备已完成配网，正在返回主页..."
            )
        }

        delay(1500L)
        closePairingFlow()
        selectDevice(selectedDevice.macAddress)
    }

    private suspend fun verifyDeviceReachable(ipAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                apiClient.create(ipAddress).getStatus()
                true
            }.getOrDefault(false)
        }
    }

    private fun buildNetworkMismatchMessage(esp32WifiSsid: String?): String {
        val esp32Wifi = esp32WifiSsid?.takeIf { it.isNotBlank() } ?: "刚才选择的 WiFi"
        val phoneWifi = getCurrentWifiSsid().takeIf { it.isNotBlank() } ?: "未获取到"
        return "ESP32 已连接 WiFi：$esp32Wifi，但手机当前无法通过 WiFi 访问设备。手机当前 WiFi：$phoneWifi。请确认手机和 ESP32 在同一个 WiFi，App 会自动重新检测。"
    }

    private fun startStatusPolling() {
        pollJob?.cancel()
    }

    private suspend fun refreshSelectedDeviceStatus(
        showErrors: Boolean,
        selectedMacOverride: String? = null
    ) {
        val selectedMac = selectedMacOverride ?: _uiState.value.selectedDeviceMac ?: return
        val selectedDevice = storedDevices.firstOrNull { it.macAddress == selectedMac } ?: return

        try {
            val service = apiClient.create(selectedDevice.ipAddress)
            val hasSensors = _uiState.value.sensorConfigs.isNotEmpty()
            val ledStatus = if (hasSensors) {
                val snapshot = service.getSnapshot()
                applySensorSnapshot(snapshot.configs)
                snapshot.status
            } else {
                service.getStatus().status
            }

            runtimeState[selectedMac] = DeviceRuntimeState(
                online = true,
                isOn = ledStatus == 1
            )
            _uiState.update { it.copy(controlError = null) }
            rebuildUiState()
        } catch (exception: Exception) {
            markDeviceOffline(selectedMac)
            if (showErrors) {
                _uiState.update {
                    it.copy(controlError = "读取设备状态失败：${exception.message ?: "网络超时"}")
                }
            }
            startMdnsWindow()
        }
    }

    private fun markDeviceOffline(macAddress: String) {
        val current = runtimeState[macAddress] ?: DeviceRuntimeState()
        runtimeState[macAddress] = current.copy(online = false)
        rebuildUiState()
    }

    private fun rebuildUiState() {
        val selectedMac = _uiState.value.selectedDeviceMac
        val devices = storedDevices.map { device ->
            val state = runtimeState[device.macAddress] ?: DeviceRuntimeState()
            DeviceUiModel(
                name = device.name,
                macAddress = device.macAddress,
                ipAddress = device.ipAddress,
                online = state.online,
                isOn = state.isOn,
                isSelected = device.macAddress == selectedMac
            )
        }
        _uiState.update { it.copy(devices = devices) }
    }

    private fun showPairingError(message: String, step: Int) {
        _uiState.update {
            it.copy(
                pairingStep = step,
                pairingBusy = false,
                pairingError = message,
                pairingMessage = message
            )
        }
    }

    private fun startBleScan() {
        bleScanJob?.cancel()
        bleScanJob = viewModelScope.launch {
            bleProvisioningManager.scanDevices()
                .catch { throwable ->
                    showPairingError(throwable.message ?: "BLE 扫描失败", 0)
                }
                .collect { devices ->
                    _uiState.update {
                        it.copy(
                            bleDevices = devices,
                            pairingMessage = if (devices.isEmpty()) {
                                "正在扫描附近的 ESP32..."
                            } else {
                                "请选择要配对的设备"
                            }
                        )
                    }
                }
        }.also { job ->
            job.invokeOnCompletion { throwable ->
                if (throwable != null) {
                    showPairingError(throwable.message ?: "BLE 扫描失败", 0)
                }
            }
        }
    }

    private fun startMdnsWindow(forceRestart: Boolean = false) {
        if (mdnsJob?.isActive == true && !forceRestart) return
        mdnsJob?.cancel()

        mdnsJob = viewModelScope.launch {
            _uiState.update { it.copy(mdnsScanning = true, showRetryButton = false) }

            val startedAt = System.currentTimeMillis()
            var resolved = false

            while (System.currentTimeMillis() - startedAt < 60_000L) {
                val selectedDevice = getSelectedStoredDevice()
                val hostName = selectedDevice?.hostName ?: DEFAULT_MDNS_HOST
                val ipAddress = mdnsResolver.resolveEsp32Host(hostName = hostName, timeoutMillis = 5_000L)

                if (!ipAddress.isNullOrBlank()) {
                    resolved = true
                    if (selectedDevice != null && selectedDevice.ipAddress != ipAddress) {
                        deviceStore.updateDeviceIp(selectedDevice.macAddress, ipAddress)
                    }
                    if (selectedDevice != null) {
                        val state = runtimeState[selectedDevice.macAddress] ?: DeviceRuntimeState()
                        runtimeState[selectedDevice.macAddress] = state.copy(online = true)
                    }
                    rebuildUiState()
                    refreshSelectedDeviceStatus(showErrors = false)
                    break
                }
                delay(3000L)
            }

            _uiState.update { it.copy(mdnsScanning = false, showRetryButton = !resolved) }
        }
    }

    private fun observeStoredDevices() {
        observeStoreJob?.cancel()
        observeStoreJob = viewModelScope.launch {
            deviceStore.snapshotFlow.collect { snapshot ->
                storedDevices = snapshot.devices
                val selectedMac = _uiState.value.selectedDeviceMac
                    ?: snapshot.selectedMacAddress
                    ?: snapshot.devices.firstOrNull()?.macAddress

                _uiState.update { it.copy(selectedDeviceMac = selectedMac) }
                rebuildUiState()
                if (selectedMac != null) {
                    refreshSensorDashboard(showErrors = false)
                    startStatusPolling()
                }
            }
        }
    }

    private fun getSelectedStoredDevice(): StoredDevice? {
        val selectedMac = _uiState.value.selectedDeviceMac ?: return null
        return storedDevices.firstOrNull { it.macAddress == selectedMac }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentWifiSsid(): String {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
        val ssid = runCatching { wifiManager.connectionInfo?.ssid.orEmpty() }.getOrDefault("")
        return ssid.removePrefix("\"")
            .removeSuffix("\"")
            .takeUnless { it.equals("<unknown ssid>", ignoreCase = true) }
            .orEmpty()
    }

    @SuppressLint("MissingPermission")
    private fun getAvailableWifiNetworks(currentSsid: String): List<WifiNetworkOption> {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        val currentFrequency = runCatching { wifiManager.connectionInfo?.frequency }.getOrNull()
        val bySsid = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
            .mapNotNull { result ->
                val ssid = result.SSID?.trim().orEmpty()
                if (ssid.isBlank()) null else ssid to result.frequency
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val networks = bySsid.entries
            .sortedBy { it.key }
            .map { (ssid, frequencies) ->
                val isCurrent = ssid == currentSsid
                WifiNetworkOption(
                    ssid = ssid,
                    isCurrent = isCurrent,
                    frequencyMhz = chooseDisplayFrequency(
                        frequencies = frequencies,
                        currentFrequency = if (isCurrent) currentFrequency else null
                    )
                )
            }
            .toMutableList()

        if (currentSsid.isNotBlank() && networks.none { it.ssid == currentSsid }) {
            networks.add(
                0,
                WifiNetworkOption(
                    ssid = currentSsid,
                    isCurrent = true,
                    frequencyMhz = currentFrequency
                )
            )
        }
        return networks
    }

    private fun chooseDisplayFrequency(
        frequencies: List<Int>,
        currentFrequency: Int?
    ): Int? {
        if (currentFrequency != null && currentFrequency > 0) return currentFrequency
        return frequencies.firstOrNull { it in 2400..2500 }
            ?: frequencies.firstOrNull { it in 4900..5900 }
            ?: frequencies.firstOrNull()
    }

    private fun updateWifiContextFromCache() {
        val current = getCurrentWifiSsid()
        val networks = getAvailableWifiNetworks(current)
        val fallback = networks.firstOrNull()?.ssid.orEmpty()

        _uiState.update { state ->
            val selected = when {
                state.selectedWifiSsid.isNotBlank() && networks.any { it.ssid == state.selectedWifiSsid } ->
                    state.selectedWifiSsid
                current.isNotBlank() -> current
                else -> fallback
            }
            state.copy(
                currentWifiSsid = current,
                selectedWifiSsid = selected,
                availableWifiNetworks = networks
            )
        }.also {
            updateSelectedWifiCompatibility(_uiState.value.selectedWifiSsid, networks)
        }
    }

    private fun updateSelectedWifiCompatibility(
        ssid: String,
        networks: List<WifiNetworkOption>
    ) {
        val selected = networks.firstOrNull { it.ssid == ssid }
        val frequency = selected?.frequencyMhz
        val pingKey = if (selected?.isTwoPointFourG == true) "$ssid:$frequency" else null
        val current = _uiState.value
        if (pingKey != null &&
            pingKey == lastWifiPingKey &&
            (current.routerPingOk != null || current.routerPingInProgress)
        ) {
            _uiState.update { it.copy(selectedWifiFrequencyMhz = frequency) }
            return
        }

        wifiPingJob?.cancel()
        lastWifiPingKey = pingKey
        _uiState.update {
            it.copy(
                selectedWifiFrequencyMhz = frequency,
                routerPingOk = null,
                routerPingInProgress = selected?.isTwoPointFourG == true
            )
        }

        if (selected?.isTwoPointFourG == true) {
            wifiPingJob = viewModelScope.launch {
                val pingOk = pingGatewayOnce()
                _uiState.update {
                    it.copy(
                        routerPingOk = pingOk,
                        routerPingInProgress = false
                    )
                }
            }
        } else {
            _uiState.update { it.copy(routerPingInProgress = false) }
        }
    }

    private suspend fun pingGatewayOnce(): Boolean? {
        return withContext(Dispatchers.IO) {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@withContext null
            @Suppress("DEPRECATION")
            val gateway = wifiManager.dhcpInfo?.gateway ?: return@withContext null
            val gatewayIp = intToIp(gateway)
            runCatching {
                val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", gatewayIp)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            }.getOrNull()
        }
    }

    private fun intToIp(value: Int): String {
        return "${value and 0xFF}.${value shr 8 and 0xFF}.${value shr 16 and 0xFF}.${value shr 24 and 0xFF}"
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

    private fun registerWifiScanReceiver() {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(wifiScanReceiver, filter)
        }
    }

    override fun onCleared() {
        wifiPingJob?.cancel()
        runCatching { appContext.unregisterReceiver(wifiScanReceiver) }
        super.onCleared()
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(application) as T
        }
    }
}

fun PinConfig.sensorKey(): String {
    return "gpio_$pin"
}

fun PinConfig.displayLabel(): String {
    return label.ifBlank { "GPIO$pin" }
}
