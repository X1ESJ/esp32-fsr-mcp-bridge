package com.example.esp32controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.esp32controller.MainActivity
import com.example.esp32controller.R
import com.example.esp32controller.data.ble.BleProvisioningManager
import com.example.esp32controller.data.fsr.FsrDataHub
import com.example.esp32controller.data.mcp.FsrMcpServer
import com.example.esp32controller.data.mdns.MdnsResolver
import com.example.esp32controller.data.network.Esp32ApiClient
import com.example.esp32controller.data.network.Esp32ApiService
import com.example.esp32controller.data.storage.DeviceStore
import com.example.esp32controller.model.DEFAULT_MDNS_HOST
import com.example.esp32controller.model.PIN_DIRECTION_INPUT
import com.example.esp32controller.model.PIN_MODE_ANALOG
import com.example.esp32controller.model.PinConfig
import com.example.esp32controller.model.StoredDevice
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CHANNEL_ID = "fsr_bridge_service"
private const val NOTIFICATION_ID = 402
private const val POLL_INTERVAL_MS = 500L
private const val FULL_REFRESH_INTERVAL_MS = 10_000L

class FsrBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val apiClient = Esp32ApiClient()

    private lateinit var deviceStore: DeviceStore
    private lateinit var mdnsResolver: MdnsResolver
    private lateinit var bleProvisioningManager: BleProvisioningManager

    private var mcpServer: FsrMcpServer? = null
    private var mcpStateJob: Job? = null
    private var pollJob: Job? = null
    private var notificationJob: Job? = null
    private var bleStatusJob: Job? = null
    private var mcpEnabled: Boolean = true
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        deviceStore = DeviceStore(applicationContext, gson)
        mdnsResolver = MdnsResolver(applicationContext)
        bleProvisioningManager = BleProvisioningManager(applicationContext)
        mcpEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_MCP_ENABLED, true)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 MCP 服务"))
        acquireLocks()
        if (mcpEnabled) {
            startMcpServer()
        } else {
            FsrDataHub.updateMcpEnabled(false)
        }
        observeDevices()
        observeNotificationState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_MCP_ENABLED) {
            setMcpEnabled(intent.getBooleanExtra(EXTRA_MCP_ENABLED, true))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        notificationJob?.cancel()
        bleStatusJob?.cancel()
        stopMcpServer()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun startMcpServer() {
        if (mcpServer != null) return
        val server = FsrMcpServer(
            context = applicationContext,
            gson = gson,
            snapshotProvider = FsrDataHub::buildMcpSnapshot,
            historyProvider = FsrDataHub::queryHistory,
            changesProvider = FsrDataHub::queryChanges,
            onSensorPost = FsrDataHub::acceptExternalSensorPayload
        )
        mcpServer = server
        server.start(scope)
        FsrDataHub.updateMcpEnabled(true)
        mcpStateJob = scope.launch {
            server.state.collect { state ->
                FsrDataHub.updateMcpState(state)
            }
        }
    }

    private fun stopMcpServer() {
        mcpStateJob?.cancel()
        mcpStateJob = null
        mcpServer?.stop()
        mcpServer = null
        FsrDataHub.updateMcpEnabled(false)
    }

    private fun setMcpEnabled(enabled: Boolean) {
        mcpEnabled = enabled
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MCP_ENABLED, enabled)
            .apply()

        if (enabled) {
            startMcpServer()
        } else {
            stopMcpServer()
        }
    }

    private fun observeDevices() {
        scope.launch {
            deviceStore.snapshotFlow.collectLatest { snapshot ->
                val selectedMac = snapshot.selectedMacAddress ?: snapshot.devices.firstOrNull()?.macAddress
                val selectedDevice = snapshot.devices.firstOrNull { it.macAddress == selectedMac }
                FsrDataHub.updateSelectedDevice(selectedDevice)
                startPolling(selectedDevice)
            }
        }
    }

    private fun startPolling(device: StoredDevice?) {
        pollJob?.cancel()
        if (device == null) {
            FsrDataHub.updateDeviceStatus(online = false)
            return
        }

        pollJob = scope.launch {
            var service = apiClient.create(device.ipAddress)
            var lastFullRefreshAt = 0L
            var lastMdnsAttemptAt = 0L

            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val needsFullRefresh =
                        FsrDataHub.state.value.sensorConfigs.isEmpty() ||
                            now - lastFullRefreshAt >= FULL_REFRESH_INTERVAL_MS

                    if (needsFullRefresh) {
                        val dashboard = service.getPins()
                        FsrDataHub.updateFromConfigs(
                            configs = dashboard.configs.onlyFsrInputs(),
                            fullSnapshot = true,
                            sampleMissingKnown = true
                        )
                        val snapshot = service.getSnapshot()
                        FsrDataHub.updateFromConfigs(
                            configs = snapshot.configs.onlyFsrInputs(),
                            fullSnapshot = true,
                            sampleMissingKnown = true
                        )
                        FsrDataHub.updateDeviceStatus(online = true, ledOn = snapshot.status == 1)
                        stopBleStatusWatch()
                        lastFullRefreshAt = now
                    } else {
                        val changes = runCatching { service.getFsrChanges() }
                            .getOrElse { service.getSnapshot() }
                        FsrDataHub.updateFromConfigs(
                            configs = changes.configs.onlyFsrInputs(),
                            fullSnapshot = false,
                            sampleMissingKnown = true
                        )
                        FsrDataHub.updateDeviceStatus(online = true, ledOn = changes.status == 1)
                        stopBleStatusWatch()
                    }
                } catch (_: Exception) {
                    FsrDataHub.updateDeviceStatus(online = false)
                    startBleStatusWatch(device)
                    val now = System.currentTimeMillis()
                    if (now - lastMdnsAttemptAt >= 5_000L) {
                        lastMdnsAttemptAt = now
                        val resolvedIp = mdnsResolver.resolveEsp32Host(
                            hostName = device.hostName.ifBlank { DEFAULT_MDNS_HOST },
                            timeoutMillis = 3_000L
                        )
                        if (!resolvedIp.isNullOrBlank() && resolvedIp != device.ipAddress) {
                            deviceStore.updateDeviceIp(device.macAddress, resolvedIp)
                            service = apiClient.create(resolvedIp)
                        }
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun List<PinConfig>.onlyFsrInputs(): List<PinConfig> {
        return filter { it.direction == PIN_DIRECTION_INPUT && it.mode == PIN_MODE_ANALOG }
    }

    private fun startBleStatusWatch(device: StoredDevice) {
        if (bleStatusJob?.isActive == true) return
        bleStatusJob = scope.launch {
            withTimeoutOrNull(60_000L) {
                bleProvisioningManager.scanStatusMessages(device.macAddress)
                    .collect { message ->
                        FsrDataHub.updateBleWifiError(message)
                    }
            }
        }
    }

    private fun stopBleStatusWatch() {
        bleStatusJob?.cancel()
        bleStatusJob = null
        FsrDataHub.updateBleWifiError(null)
    }

    private fun observeNotificationState() {
        notificationJob = scope.launch {
            FsrDataHub.state.collect { state ->
                val mcp = state.mcpState
                val text = if (!mcp.enabled) {
                    "MCP 已关闭 · FSR 采集中"
                } else if (mcp.running) {
                    "${mcp.host}:${mcp.port} · ${state.sensorReadings.size} 路传感器"
                } else {
                    mcp.error ?: "MCP 未运行"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fsr:mcp:wifi")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fsr:mcp:cpu")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FSR MCP 常驻服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 FSR 采集和 MCP 服务在后台运行"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (text.startsWith("MCP 已关闭")) "FSR 采集服务运行中" else "FSR MCP 服务运行中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val PREFS_NAME = "fsr_bridge_service"
        private const val KEY_MCP_ENABLED = "mcp_enabled"
        private const val ACTION_SET_MCP_ENABLED = "com.example.esp32controller.SET_MCP_ENABLED"
        private const val EXTRA_MCP_ENABLED = "mcp_enabled"

        fun start(context: Context) {
            val intent = Intent(context, FsrBridgeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setMcpEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, FsrBridgeService::class.java).apply {
                action = ACTION_SET_MCP_ENABLED
                putExtra(EXTRA_MCP_ENABLED, enabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
