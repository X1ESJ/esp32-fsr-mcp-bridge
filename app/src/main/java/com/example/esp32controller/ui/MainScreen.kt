package com.example.esp32controller.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.esp32controller.BuildConfig
import com.example.esp32controller.model.BleScanDevice
import com.example.esp32controller.model.DeviceUiModel
import com.example.esp32controller.model.FSR_ANALOG_MAX_VALUE
import com.example.esp32controller.model.FSR_SENSOR_PINS
import com.example.esp32controller.model.FsrBridgeSettings
import com.example.esp32controller.model.FsrDatabaseStats
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.McpServerState
import com.example.esp32controller.model.PinConfig
import com.example.esp32controller.model.PinHistoryPoint
import com.example.esp32controller.model.SupabaseSettings
import com.example.esp32controller.model.SupabaseSyncState
import com.example.esp32controller.model.WifiNetworkOption
import com.example.esp32controller.viewmodel.MainUiState
import com.example.esp32controller.viewmodel.displayLabel
import com.example.esp32controller.viewmodel.sensorKey
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF7FAFC) else Color(0xFF101828)
private val Muted: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9AA8BA) else Color(0xFF667085)
private val Line: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2B3A50) else Color(0xFFD9E1EA)
private val Panel: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xE6142030) else Color.White.copy(alpha = 0.94f)
private val ActiveBlue: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7DB3FF) else Color(0xFF2563EB)
private val GoodGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4ADE80) else Color(0xFF22C55E)
private val WarmOrange: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFB86B) else Color(0xFFF97316)
private val DangerRed: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF7A7A) else Color(0xFFDC2626)
private val AppBackground: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF09111C) else Color(0xFFF4F7FB)
private val ScreenSurface: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF08111D) else Color(0xFFF7FAFF)
private val BackgroundGradient: List<Color>
    @Composable get() = if (isSystemInDarkTheme()) {
        listOf(Color(0xFF0B1422), Color(0xFF111D2E))
    } else {
        listOf(Color(0xFFF9FBFF), Color(0xFFEAF0F7))
    }
private val CardInner: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF162234) else Color(0xFFF8FAFC)
private val SelectedSurface: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF18345A) else Color(0xFFEAF2FF)
private val DisabledDot: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF65758B) else Color(0xFF98A2B3)
private val LevelTrack: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF243247) else Color(0xFFE4EAF2)
private val ChartGrid: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF263850) else Color(0xFFE4EAF2)
private val BottomBarColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xF20B1422) else Color.White.copy(alpha = 0.9f)
private val CardBorder: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF25364C) else Color.White.copy(alpha = 0.72f)
private val StepInactive: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C3B50) else Color(0xFFD8E0EA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenPairing: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onOpenSensorPanel: () -> Unit,
    onCloseSensorPanel: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onUpdateHistoryWindow: (Long) -> Unit,
    onUpdateSampleInterval: (Long) -> Unit,
    onUpdateTriggerThreshold: (Int) -> Unit,
    onUpdateSupabaseSettings: (SupabaseSettings) -> Unit,
    onExportDatabase: () -> Unit,
    onSaveFsrSensor: (Int, String) -> Unit,
    onDeleteSensor: (PinConfig) -> Unit,
    onRetryMdns: () -> Unit,
    onClearMessage: () -> Unit,
    onClosePairing: () -> Unit,
    onSelectBleDevice: (BleScanDevice) -> Unit,
    onGoToWifiStep: () -> Unit,
    onPairingBack: (Int) -> Unit,
    onConfirmWifi: (String) -> Unit,
    onRefreshWifiList: () -> Unit,
    onSelectWifiNetwork: (String) -> Unit,
    onSetMcpEnabled: (Boolean) -> Unit,
    onExitApp: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var toolsDetailVisible by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeviceUiModel?>(null) }
    var lastBackAt by remember { mutableStateOf(0L) }

    LaunchedEffect(uiState.pairingError, uiState.controlError) {
        val message = uiState.pairingError ?: uiState.controlError
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            onClearMessage()
        }
    }

    BackHandler {
        when {
            uiState.pairingVisible -> {
                if (uiState.pairingStep == 0) onClosePairing() else onPairingBack(uiState.pairingStep)
            }
            uiState.sensorPanelVisible -> onCloseSensorPanel()
            uiState.settingsVisible -> onCloseSettings()
            toolsDetailVisible -> toolsDetailVisible = false
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt <= 2000L) {
                    onExitApp()
                } else {
                    lastBackAt = now
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar("再次返回退出应用")
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            if (!uiState.pairingVisible && !uiState.sensorPanelVisible && !uiState.settingsVisible && !toolsDetailVisible) {
                MainTopBar(
                    onOpenPairing = onOpenPairing,
                    onOpenSettings = onOpenSettings
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!uiState.pairingVisible && !uiState.sensorPanelVisible && !uiState.settingsVisible && !toolsDetailVisible) {
                DetailBottomBar(
                    expanded = detailsExpanded,
                    onToggle = { detailsExpanded = !detailsExpanded }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        BackgroundGradient
                    )
                )
                .padding(paddingValues)
        ) {
            MainDashboard(
                uiState = uiState,
                hasPermissions = hasPermissions,
                onRequestPermissions = onRequestPermissions,
                onSelectDevice = onSelectDevice,
                onDeleteDevice = { deleteTarget = it },
                onOpenSensorPanel = onOpenSensorPanel,
                onRetryMdns = onRetryMdns,
                onSetMcpEnabled = onSetMcpEnabled,
                onOpenToolDetails = { toolsDetailVisible = true }
            )

            AnimatedVisibility(visible = uiState.sensorPanelVisible && !uiState.pairingVisible) {
                SensorPanelScreen(
                    uiState = uiState,
                    selectedDevice = uiState.devices.firstOrNull { it.isSelected },
                    onClose = onCloseSensorPanel,
                    onSaveFsrSensor = onSaveFsrSensor,
                    onDeleteSensor = onDeleteSensor
                )
            }

            AnimatedVisibility(visible = uiState.settingsVisible && !uiState.pairingVisible && !uiState.sensorPanelVisible) {
                SettingsScreen(
                    uiState = uiState,
                    onClose = onCloseSettings,
                    onUpdateHistoryWindow = onUpdateHistoryWindow,
                    onUpdateSampleInterval = onUpdateSampleInterval,
                    onUpdateTriggerThreshold = onUpdateTriggerThreshold,
                    onUpdateSupabaseSettings = onUpdateSupabaseSettings,
                    onExportDatabase = onExportDatabase
                )
            }

            AnimatedVisibility(visible = uiState.pairingVisible) {
                PairingFlowScreen(
                    uiState = uiState,
                    onClose = onClosePairing,
                    onSelectBleDevice = onSelectBleDevice,
                    onGoToWifiStep = onGoToWifiStep,
                    onBack = onPairingBack,
                    onConfirmWifi = onConfirmWifi,
                    onRefreshWifiList = onRefreshWifiList,
                    onSelectWifiNetwork = onSelectWifiNetwork
                )
            }

            AnimatedVisibility(visible = toolsDetailVisible && !uiState.pairingVisible && !uiState.sensorPanelVisible && !uiState.settingsVisible) {
                ToolsDetailScreen(onClose = { toolsDetailVisible = false })
            }
        }
    }

    deleteTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除设备") },
            text = { Text("确定删除 ${device.name} 吗？删除后需要重新配对才能恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteDevice(device.macAddress)
                        deleteTarget = null
                    }
                ) {
                    Text("删除", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("FSR 娃娃感知", fontWeight = FontWeight.Bold, color = Ink)
                Text("ESP32-S3 · 本地 MCP", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
            Box(
                modifier = Modifier.padding(end = 34.dp)
            ) {
                Button(
                    onClick = onOpenPairing,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("配对")
                }
            }
        }
    )
}

@Composable
private fun MainDashboard(
    uiState: MainUiState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onDeleteDevice: (DeviceUiModel) -> Unit,
    onOpenSensorPanel: () -> Unit,
    onRetryMdns: () -> Unit,
    onSetMcpEnabled: (Boolean) -> Unit,
    onOpenToolDetails: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasPermissions) {
            item {
                PermissionCard(onRequestPermissions = onRequestPermissions)
            }
        }

        item {
            DeviceListCard(
                devices = uiState.devices,
                mdnsScanning = uiState.mdnsScanning,
                showRetryButton = uiState.showRetryButton,
                onSelectDevice = onSelectDevice,
                onDeleteDevice = onDeleteDevice,
                onRetryMdns = onRetryMdns
            )
        }

        if (!uiState.bleWifiError.isNullOrBlank()) {
            item {
                BleWifiErrorCard(message = uiState.bleWifiError)
            }
        }

        item {
            McpStatusCard(
                mcpState = uiState.mcpState,
                sensorCount = uiState.sensorReadings.size,
                onSetMcpEnabled = onSetMcpEnabled,
                onOpenToolDetails = onOpenToolDetails
            )
        }

        item {
            SensorOverviewCard(
                selectedDevice = uiState.devices.firstOrNull { it.isSelected },
                readings = uiState.sensorReadings,
                onOpenSensorPanel = onOpenSensorPanel
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    FrostedCard {
        Text("需要权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("BLE 配网、WiFi 扫描和 mDNS 发现需要系统权限。", color = Muted)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequestPermissions, shape = RoundedCornerShape(14.dp)) {
            Text("授予权限")
        }
    }
}

@Composable
private fun DeviceListCard(
    devices: List<DeviceUiModel>,
    mdnsScanning: Boolean,
    showRetryButton: Boolean,
    onSelectDevice: (String) -> Unit,
    onDeleteDevice: (DeviceUiModel) -> Unit,
    onRetryMdns: () -> Unit
) {
    FrostedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    if (devices.isEmpty()) "还没有配对设备" else "选择要采集 FSR 数据的 ESP32",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
            if (mdnsScanning) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else if (showRetryButton) {
                OutlinedButton(onClick = onRetryMdns, shape = RoundedCornerShape(999.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (devices.isEmpty()) {
            Text("点击右上角“配对”开始。", color = Muted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        onClick = { onSelectDevice(device.macAddress) },
                        onDelete = { onDeleteDevice(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BleWifiErrorCard(message: String) {
    FrostedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                good = false,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("设备 WiFi 连接失败", fontWeight = FontWeight.Bold, color = Ink)
                Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceUiModel,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (device.isSelected) SelectedSurface else CardInner)
            .border(
                width = 1.dp,
                color = if (device.isSelected) ActiveBlue.copy(alpha = 0.35f) else Line,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (device.online) GoodGreen else DisabledDot)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(device.ipAddress, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(device.macAddress, style = MaterialTheme.typography.bodySmall, color = Muted.copy(alpha = 0.78f))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除设备", tint = DangerRed)
        }
    }
}

@Composable
private fun StatusBadge(
    good: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (good) GoodGreen else DangerRed
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        drawCircle(color = color)
        if (good) {
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.28f, size.height * 0.52f),
                end = Offset(size.width * 0.44f, size.height * 0.68f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.44f, size.height * 0.68f),
                end = Offset(size.width * 0.74f, size.height * 0.34f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        } else {
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.5f, size.height * 0.26f),
                end = Offset(size.width * 0.5f, size.height * 0.58f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = Color.White,
                radius = stroke * 0.55f,
                center = Offset(size.width * 0.5f, size.height * 0.75f)
            )
        }
    }
}

@Composable
private fun McpStatusCard(
    mcpState: McpServerState,
    sensorCount: Int,
    onSetMcpEnabled: (Boolean) -> Unit,
    onOpenToolDetails: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    FrostedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !mcpState.enabled -> DisabledDot
                            mcpState.running -> GoodGreen
                            else -> DangerRed
                        }
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("MCP 服务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    when {
                        !mcpState.enabled -> "已关闭，FSR 采集仍在后台运行"
                        mcpState.running -> "运行中，当前缓存 $sensorCount 路传感器"
                        else -> "未运行"
                    },
                    color = Muted
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("开启 MCP", color = Muted, style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = mcpState.enabled,
                    onCheckedChange = onSetMcpEnabled
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        InfoLine("MCP 地址", mcpState.url)
        InfoLine("ESP32 推送地址", "http://${mcpState.host}:${mcpState.port}/hello")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(mcpState.url))
                    copied = true
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(if (copied) "已复制" else "复制地址")
            }
            OutlinedButton(
                onClick = onOpenToolDetails,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("查看工具详情")
            }
        }
        if (!mcpState.error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(mcpState.error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Ink)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SensorOverviewCard(
    selectedDevice: DeviceUiModel?,
    readings: List<FsrSensorReading>,
    onOpenSensorPanel: () -> Unit
) {
    FrostedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FSR 数据", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    selectedDevice?.let { if (it.online) "已连接 ${it.ipAddress}" else "设备离线" } ?: "请选择设备",
                    color = Muted
                )
            }
            Button(
                onClick = onOpenSensorPanel,
                enabled = selectedDevice != null,
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("配置")
            }
        }

        Spacer(Modifier.height(14.dp))
        if (readings.isEmpty()) {
            EmptySensorHint()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                readings.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { reading ->
                            MiniReadingCard(
                                reading = reading,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySensorHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardInner)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("还没有传感器数据，进入配置页添加 GPIO1-GPIO10。", color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MiniReadingCard(
    reading: FsrSensorReading,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardInner)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(reading.label, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            DeltaChip(reading.delta)
        }
        Spacer(Modifier.height(8.dp))
        RollingNumberText(
            value = reading.value,
            style = MaterialTheme.typography.headlineSmall,
            color = Ink
        )
        Spacer(Modifier.height(8.dp))
        LevelBar(reading.normalized)
        Spacer(Modifier.height(6.dp))
        Text(reading.source, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorPanelScreen(
    uiState: MainUiState,
    selectedDevice: DeviceUiModel?,
    onClose: () -> Unit,
    onSaveFsrSensor: (Int, String) -> Unit,
    onDeleteSensor: (PinConfig) -> Unit
) {
    var addDialogVisible by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ScreenSurface
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("传感器配置", fontWeight = FontWeight.Bold)
                            Text("GPIO1-GPIO10 · 模拟输入 · 12 位", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { addDialogVisible = true },
                            enabled = selectedDevice != null && uiState.sensorConfigs.size < FSR_SENSOR_PINS.size
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加传感器")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { addDialogVisible = true },
                    containerColor = ActiveBlue,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加传感器")
                }
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    FrostedCard {
                        Text("接线范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "FSR402 接到 ESP32-S3 的 ADC1 引脚：GPIO1、2、3、4、5、6、7、8、9、10。保存后 App 会实时读取数值和变化量。",
                            color = Muted
                        )
                        if (uiState.pinBusy) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在同步设备...", color = Muted)
                            }
                        }
                    }
                }

                if (uiState.sensorConfigs.isEmpty()) {
                    item {
                        EmptyControlPanel(onAdd = { addDialogVisible = true })
                    }
                } else {
                    items(uiState.sensorConfigs, key = { it.sensorKey() }) { config ->
                        val reading = uiState.sensorReadings.firstOrNull { it.key == config.sensorKey() }
                        SensorConfigCard(
                            config = config,
                            reading = reading,
                            history = uiState.sensorHistory[config.sensorKey()].orEmpty(),
                            onDelete = { onDeleteSensor(config) }
                        )
                    }
                }
            }
        }
    }

    if (addDialogVisible) {
        AddSensorDialog(
            usedPins = uiState.sensorConfigs.map { it.pin }.toSet(),
            usedNames = uiState.sensorConfigs.map { it.displayLabel() }.toSet(),
            onDismiss = { addDialogVisible = false },
            onSave = { pin, label ->
                onSaveFsrSensor(pin, label)
                addDialogVisible = false
            }
        )
    }
}

@Composable
private fun EmptyControlPanel(onAdd: () -> Unit) {
    FrostedCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Timeline, contentDescription = null, tint = ActiveBlue, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text("还没有 FSR 传感器", fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text("点击添加，选择 GPIO1-GPIO10。", color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAdd, shape = RoundedCornerShape(999.dp)) {
                Text("添加传感器")
            }
        }
    }
}

@Composable
private fun SensorConfigCard(
    config: PinConfig,
    reading: FsrSensorReading?,
    history: List<PinHistoryPoint>,
    onDelete: () -> Unit
) {
    val value = reading?.value ?: config.value
    val delta = reading?.delta ?: 0
    val normalized = (value.toFloat() / FSR_ANALOG_MAX_VALUE.toFloat()).coerceIn(0f, 1f)

    FrostedCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(config.displayLabel(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text("GPIO${config.pin} · 模拟输入 · ADC1", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = DangerRed)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            RollingNumberText(
                value = value,
                style = MaterialTheme.typography.displaySmall,
                color = Ink
            )
            Spacer(Modifier.width(10.dp))
            DeltaChip(delta)
        }
        Spacer(Modifier.height(12.dp))
        LevelBar(normalized)
        Spacer(Modifier.height(14.dp))
        FsrHistoryChart(history)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    uiState: MainUiState,
    onClose: () -> Unit,
    onUpdateHistoryWindow: (Long) -> Unit,
    onUpdateSampleInterval: (Long) -> Unit,
    onUpdateTriggerThreshold: (Int) -> Unit,
    onUpdateSupabaseSettings: (SupabaseSettings) -> Unit,
    onExportDatabase: () -> Unit
) {
    Surface(color = ScreenSurface, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("设置", fontWeight = FontWeight.Bold)
                            Text("采样、历史、导出与 Supabase", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    SamplingSettingsCard(
                        settings = uiState.settings,
                        onUpdateHistoryWindow = onUpdateHistoryWindow,
                        onUpdateSampleInterval = onUpdateSampleInterval,
                        onUpdateTriggerThreshold = onUpdateTriggerThreshold
                    )
                }
                item {
                    LocalDatabaseCard(
                        stats = uiState.databaseStats,
                        exportBusy = uiState.exportBusy,
                        exportMessage = uiState.exportMessage,
                        onExportDatabase = onExportDatabase
                    )
                }
                item {
                    SupabaseSettingsCard(
                        settings = uiState.settings.supabase,
                        syncState = uiState.supabaseSyncState,
                        onUpdateSupabaseSettings = onUpdateSupabaseSettings
                    )
                }
            }
        }
    }
}

@Composable
private fun SamplingSettingsCard(
    settings: FsrBridgeSettings,
    onUpdateHistoryWindow: (Long) -> Unit,
    onUpdateSampleInterval: (Long) -> Unit,
    onUpdateTriggerThreshold: (Int) -> Unit
) {
    var customHistorySeconds by rememberSaveable(settings.historyWindowMs) {
        mutableStateOf((settings.historyWindowMs / 1000L).toString())
    }
    var customIntervalMs by rememberSaveable(settings.sampleIntervalMs) {
        mutableStateOf(settings.sampleIntervalMs.toString())
    }
    var thresholdText by rememberSaveable(settings.triggerThreshold) {
        mutableStateOf(settings.triggerThreshold.toString())
    }

    FrostedCard {
        Text("采样与短期历史", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("短期历史给实时 MCP 工具使用；长期记录会持续写入手机本地数据库。", color = Muted)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricPill(
                title = "短期历史",
                value = (settings.historyWindowMs / 1000L).toInt(),
                unit = "秒",
                modifier = Modifier.weight(1f)
            )
            MetricPill(
                title = "保存间隔",
                value = settings.sampleIntervalMs.toInt(),
                unit = "毫秒",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("短期历史保存时间", fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        ChoiceGrid(
            options = listOf("15 秒" to 15_000L, "30 秒" to 30_000L, "45 秒" to 45_000L, "60 秒" to 60_000L),
            selected = settings.historyWindowMs,
            onSelect = onUpdateHistoryWindow
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = customHistorySeconds,
                onValueChange = { customHistorySeconds = it.filter { char -> char.isDigit() }.take(6) },
                label = { Text("自定义秒数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    customHistorySeconds.toLongOrNull()?.let { onUpdateHistoryWindow(it * 1000L) }
                },
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("应用")
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("保存频率", fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        ChoiceGrid(
            options = listOf("0.25 秒" to 250L, "0.5 秒" to 500L, "1 秒" to 1_000L, "1.5 秒" to 1_500L),
            selected = settings.sampleIntervalMs,
            onSelect = onUpdateSampleInterval
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = customIntervalMs,
                onValueChange = { customIntervalMs = it.filter { char -> char.isDigit() }.take(6) },
                label = { Text("自定义毫秒") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    customIntervalMs.toLongOrNull()?.let(onUpdateSampleInterval)
                },
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("应用")
            }
        }

        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = thresholdText,
            onValueChange = { thresholdText = it.filter { char -> char.isDigit() }.take(4) },
            label = { Text("触发阈值") },
            supportingText = { Text("默认 300。超过阈值才进入戳、按、抱住不放等事件判断。") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { thresholdText.toIntOrNull()?.let(onUpdateTriggerThreshold) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("保存阈值")
        }
    }
}

@Composable
private fun LocalDatabaseCard(
    stats: FsrDatabaseStats,
    exportBusy: Boolean,
    exportMessage: String?,
    onExportDatabase: () -> Unit
) {
    FrostedCard {
        Text("本地数据库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("采样数据会追加写入 App 私有数据区；导出不会删除旧记录，也不会停止新记录写入。", color = Muted)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricPill("采样行", stats.sampleRows.toIntSafe(), "行", Modifier.weight(1f))
            MetricPill("事件", stats.eventRows.toIntSafe(), "条", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricPill("分钟摘要", stats.minuteRows.toIntSafe(), "条", Modifier.weight(1f))
            MetricPill("待上传", stats.pendingUploads.toIntSafe(), "条", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        InfoLine("最近采样", stats.lastSampleAtMs?.let(::formatClockTime) ?: "还没有数据")
        Button(
            onClick = onExportDatabase,
            enabled = !exportBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(if (exportBusy) "正在导出..." else "导出 JSON")
        }
        if (!exportMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(exportMessage, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SupabaseSettingsCard(
    settings: SupabaseSettings,
    syncState: SupabaseSyncState,
    onUpdateSupabaseSettings: (SupabaseSettings) -> Unit
) {
    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var projectUrl by rememberSaveable(settings.projectUrl) { mutableStateOf(settings.projectUrl) }
    var anonKey by rememberSaveable(settings.anonKey) { mutableStateOf(settings.anonKey) }
    var minuteTable by rememberSaveable(settings.minuteDataTable) { mutableStateOf(settings.minuteDataTable) }
    var sessionsTable by rememberSaveable(settings.sessionsTable) { mutableStateOf(settings.sessionsTable) }

    fun currentSettings(nextEnabled: Boolean = enabled): SupabaseSettings {
        return SupabaseSettings(
            enabled = nextEnabled,
            projectUrl = projectUrl,
            anonKey = anonKey,
            minuteDataTable = minuteTable,
            sessionsTable = sessionsTable
        )
    }

    FrostedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Supabase 云同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                Text("App 每分钟直传聚合数据，MCP 只负责查询。", color = Muted)
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    onUpdateSupabaseSettings(currentSettings(nextEnabled = it))
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = projectUrl,
            onValueChange = { projectUrl = it },
            label = { Text("项目地址") },
            placeholder = { Text("https://xxxxx.supabase.co") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = anonKey,
            onValueChange = { anonKey = it },
            label = { Text("anon key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = minuteTable,
                onValueChange = { minuteTable = it },
                label = { Text("分钟表") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = sessionsTable,
                onValueChange = { sessionsTable = it },
                label = { Text("会话表") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onUpdateSupabaseSettings(currentSettings()) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("保存 Supabase 设置")
        }
        Spacer(Modifier.height(10.dp))
        val statusText = when {
            !syncState.enabled -> "云同步已关闭"
            !syncState.configured -> "请填写项目地址和 anon key"
            syncState.syncing -> "正在同步..."
            !syncState.lastError.isNullOrBlank() -> syncState.lastError
            !syncState.lastMessage.isNullOrBlank() -> syncState.lastMessage
            else -> "等待下一次分钟同步"
        }
        Text(statusText, color = if (syncState.lastError.isNullOrBlank()) Muted else DangerRed)
        syncState.lastSyncAtMs?.let {
            Text("上次同步：${formatClockTime(it)}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChoiceGrid(
    options: List<Pair<String, Long>>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    val active = selected == value
                    OutlinedButton(
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (active) SelectedSurface else Color.Transparent,
                            contentColor = if (active) ActiveBlue else Ink
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(label)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricPill(
    title: String,
    value: Int,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardInner)
            .border(1.dp, Line, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            RollingNumberText(
                value = value,
                style = MaterialTheme.typography.headlineSmall,
                color = Ink
            )
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun AddSensorDialog(
    usedPins: Set<Int>,
    usedNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Int, String) -> Unit
) {
    val availablePins = FSR_SENSOR_PINS.filterNot { it in usedPins }
    var selectedPin by remember { mutableStateOf(availablePins.firstOrNull()) }
    var label by rememberSaveable { mutableStateOf(selectedPin?.let { "GPIO$it" }.orEmpty()) }
    val trimmedLabel = label.trim()
    val duplicatedName = usedNames.any { it.equals(trimmedLabel, ignoreCase = true) }
    val canSave = selectedPin != null && trimmedLabel.isNotBlank() && !duplicatedName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 FSR 传感器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("选择一个 GPIO，App 会把它配置成 12 位模拟输入。", color = Muted)
                PinDropdown(
                    selectedPin = selectedPin,
                    availablePins = availablePins,
                    onSelectPin = {
                        selectedPin = it
                        if (label.isBlank() || label.startsWith("GPIO")) label = "GPIO$it"
                    }
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("传感器名称") },
                    singleLine = true,
                    isError = trimmedLabel.isBlank() || duplicatedName,
                    supportingText = {
                        Text(
                            when {
                                trimmedLabel.isBlank() -> "名称会作为 MCP tool 的 name 参数，不能为空。"
                                duplicatedName -> "名称已存在，请换一个。"
                                else -> "第三方 AI 可用这个名称查询数据。"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedPin?.let { onSave(it, trimmedLabel) } },
                enabled = canSave
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PinDropdown(
    selectedPin: Int?,
    availablePins: List<Int>,
    onSelectPin: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = availablePins.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(selectedPin?.let { "GPIO$it" } ?: "没有可用 GPIO", modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availablePins.forEach { pin ->
                DropdownMenuItem(
                    text = { Text("GPIO$pin") },
                    onClick = {
                        onSelectPin(pin)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun RollingNumberText(
    value: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val direction = if (targetState >= initialState) 1 else -1
            (
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) { fullHeight -> direction * fullHeight } + fadeIn()
                ).togetherWith(
                    slideOutVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { fullHeight -> -direction * fullHeight } + fadeOut()
                )
        },
        label = "rolling-number",
        modifier = modifier
    ) { number ->
        Text(
            text = number.toString(),
            style = style,
            fontWeight = FontWeight.Black,
            color = color
        )
    }
}

@Composable
private fun DeltaChip(delta: Int) {
    val color = when {
        delta > 0 -> GoodGreen
        delta < 0 -> WarmOrange
        else -> Muted
    }
    val sign = when {
        delta > 0 -> "+"
        else -> ""
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text("$sign$delta", color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

private fun Long.toIntSafe(): Int {
    return coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

private fun formatClockTime(timestampMs: Long): String {
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}

@Composable
private fun LevelBar(value: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(LevelTrack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Brush.horizontalGradient(listOf(ActiveBlue, GoodGreen)))
        )
    }
}

@Composable
private fun FsrHistoryChart(history: List<PinHistoryPoint>) {
    val sorted = history.sortedBy { it.second }
    val chartSurface = CardInner
    val chartBorder = Line
    val gridColor = ChartGrid
    val pathColor = ActiveBlue
    val pointerColor = GoodGreen
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(chartSurface)
            .border(1.dp, chartBorder, RoundedCornerShape(18.dp))
            .padding(10.dp)
    ) {
        val left = 18f
        val right = size.width - 8f
        val top = 10f
        val bottom = size.height - 22f

        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(
                color = gridColor,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1f
            )
        }

        if (sorted.size >= 2) {
            val minSecond = sorted.first().second
            val maxSecond = (minSecond + 120).coerceAtLeast(sorted.last().second)
            val path = Path()
            sorted.forEachIndexed { index, point ->
                val xProgress = if (maxSecond == minSecond) 0f else {
                    (point.second - minSecond).toFloat() / (maxSecond - minSecond).toFloat()
                }
                val x = left + (right - left) * xProgress.coerceIn(0f, 1f)
                val y = bottom - (bottom - top) * (point.value.toFloat() / FSR_ANALOG_MAX_VALUE.toFloat()).coerceIn(0f, 1f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = pathColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = StrokeCap.Round))
        }

        val recent = sorted.lastOrNull()
        if (recent != null) {
            drawCircle(
                color = pointerColor,
                radius = 5f,
                center = Offset(
                    x = right,
                    y = bottom - (bottom - top) * (recent.value.toFloat() / FSR_ANALOG_MAX_VALUE.toFloat()).coerceIn(0f, 1f)
                )
            )
        }
    }
}

private data class McpToolDoc(
    val name: String,
    val purpose: String,
    val setting: String,
    val inputs: List<String>,
    val returns: List<String>
)

private val mcpToolDocs = listOf(
    McpToolDoc(
        name = "fsr_list_sensors",
        purpose = "列出 App 当前已配置的全部 FSR402 传感器。",
        setting = "无传入参数，适合 AI 先确认用户给各个 GPIO 起了什么名字。",
        inputs = listOf("无"),
        returns = listOf("deviceOnline：ESP32 当前是否在线", "resolutionBits/maxValue：ADC 分辨率和最大值", "sensors：传感器名称、GPIO、key、来源")
    ),
    McpToolDoc(
        name = "fsr_get_snapshot",
        purpose = "紧凑读取所有传感器当前最新值。",
        setting = "适合回答“现在娃娃哪里被按压了”这类实时问题；身份信息请先看 fsr_list_sensors。",
        inputs = listOf("无"),
        returns = listOf("t：快照时间", "online/max：在线状态和 ADC 最大值", "cols=[s,v,d,ageMs]", "data：传感器名、当前值、带正负号差值、数据年龄")
    ),
    McpToolDoc(
        name = "fsr_get_sensor",
        purpose = "紧凑读取单个传感器的当前值。",
        setting = "推荐优先传入 App 中用户命名的 name，例如“左耳”。",
        inputs = listOf("name：用户命名的传感器名称", "pin：GPIO 编号，例如 4", "key：内部 key，例如 gpio_4", "label：兼容旧字段，等同于 name"),
        returns = listOf("s：传感器名", "v：当前值", "d：带正负号差值", "ageMs：数据年龄", "找不到时返回 error=sensor_not_found")
    ),
    McpToolDoc(
        name = "fsr_get_changes",
        purpose = "紧凑读取上次调用之后发生变化的数据。",
        setting = "适合聊天应用持续观察触摸变化；身份信息不重复返回，先用 fsr_list_sensors 看映射。",
        inputs = listOf("cursor：上次返回的 next，不传则 App 自动接着上次位置", "name/names：只观察指定传感器", "min_delta：最小变化量，默认 8"),
        returns = listOf("cursor/next/minD：游标和阈值", "t0：基准时间戳", "cols=[s,dt,v,d]", "data：传感器名、相对 t0 的毫秒偏移、当前值、带正负号差值")
    ),
    McpToolDoc(
        name = "fsr_get_history",
        purpose = "读取 App 私有数据区的短期历史缓存。",
        setting = "默认返回压缩段，尽量省 token；需要原始点时传 mode=raw 或 includeRaw=true。",
        inputs = listOf("name/names：筛选传感器", "lastMs：最近多少毫秒，默认跟随设置里的短期历史", "fromMs/toMs：时间戳范围", "intervalMs：抽样间隔，默认跟随保存频率", "compressionTolerance：稳定段压缩容差，默认 15", "mode：默认 segments，可传 raw"),
        returns = listOf("t0/to/max/mode：时间基准、结束时间、ADC 最大值、返回模式", "segments 模式 cols=[from,to,v]", "raw 模式 cols=[t,v]", "series：每个传感器的紧凑数组")
    ),
    McpToolDoc(
        name = "fsr_list_sessions",
        purpose = "读取手机本地数据库里的最近触摸会话摘要。",
        setting = "适合白天问 AI 昨晚有没有被抱、被按或被摸；默认只给摘要，不吐原始点。",
        inputs = listOf("limit：最多返回多少条，默认 12", "sinceMs：只看某个时间戳之后"),
        returns = listOf("cols=[id,from,to,durS,max,summary]", "data：会话 id、开始、结束、持续秒数、峰值、摘要")
    ),
    McpToolDoc(
        name = "fsr_get_session_summary",
        purpose = "读取一个会话的摘要和少量事件。",
        setting = "不传 id 时默认读取最近会话；需要深挖时再调用这个工具。",
        inputs = listOf("id：会话 id", "limit：最多带回多少条事件，默认 40"),
        returns = listOf("id/from/to/durS/avg/max/counts/summary", "eventCols=[dt,type,s,durMs,peak]", "events：相对会话开始时间的事件列表")
    ),
    McpToolDoc(
        name = "fsr_get_events",
        purpose = "按时间窗口读取已分类的触摸事件。",
        setting = "事件由 App 本地规则生成，AI 不需要从原始 ADC 点里猜。",
        inputs = listOf("fromMs/toMs：时间戳范围", "lastMs：最近多少毫秒，默认 8 小时", "name/type/sessionId：可筛选", "limit：最多返回多少条，默认 80"),
        returns = listOf("t0：窗口开始", "cols=[dt,type,s,durMs,peak]", "data：相对时间、类型、传感器、持续毫秒、峰值")
    ),
    McpToolDoc(
        name = "fsr_get_window",
        purpose = "读取长期数据库里的分钟级摘要窗口。",
        setting = "适合看整晚趋势，默认返回分钟摘要，不返回海量原始采样。",
        inputs = listOf("fromMs/toMs：时间戳范围", "lastMs：最近多少毫秒，默认 8 小时", "limit：最多返回多少个分钟点，默认 480"),
        returns = listOf("t0/to/mode/cols", "data：分钟偏移、摘要、该分钟采样数")
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsDetailScreen(onClose: () -> Unit) {
    Surface(color = ScreenSurface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("MCP 工具详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        "第三方 AI 应用通过这些 MCP 工具读取手机本地短期缓存、长期会话摘要和当前 FSR 数据。工具名称需要保持英文，参数建议优先使用 App 中的中文传感器名称。",
                        color = Muted
                    )
                }
                items(mcpToolDocs) { tool ->
                    ToolDocCard(tool)
                }
            }
        }
    }
}

@Composable
private fun ToolDocCard(tool: McpToolDoc) {
    FrostedCard {
        Text(tool.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ActiveBlue)
        Spacer(Modifier.height(10.dp))
        ToolInfoLine("功能", tool.purpose)
        ToolInfoLine("设定", tool.setting)
        ToolInfoLine("传入值", tool.inputs.joinToString("\n"))
        ToolInfoLine("返回值", tool.returns.joinToString("\n"))
    }
}

@Composable
private fun ToolInfoLine(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
    Text(value, style = MaterialTheme.typography.bodyMedium, color = Ink)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DetailBottomBar(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        tonalElevation = 12.dp,
        color = BottomBarColor,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedButton(onClick = onToggle, shape = RoundedCornerShape(999.dp)) {
                Text("详情")
            }
            AnimatedVisibility(visible = expanded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    val rainbow = Brush.linearGradient(
                        listOf(Color(0xFFE11D48), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF2563EB))
                    )
                    Text(
                        "琳云 XESJ",
                        style = MaterialTheme.typography.bodyMedium.copy(brush = rainbow),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "xianeshijie@outlook.com",
                        style = MaterialTheme.typography.bodySmall.copy(brush = rainbow),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "版本：${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall.copy(brush = rainbow),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun FrostedCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(28.dp))
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun PairingFlowScreen(
    uiState: MainUiState,
    onClose: () -> Unit,
    onSelectBleDevice: (BleScanDevice) -> Unit,
    onGoToWifiStep: () -> Unit,
    onBack: (Int) -> Unit,
    onConfirmWifi: (String) -> Unit,
    onRefreshWifiList: () -> Unit,
    onSelectWifiNetwork: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = ScreenSurface) {
        Column(modifier = Modifier.fillMaxSize()) {
            PairingTopBar(
                step = uiState.pairingStep,
                onClose = onClose,
                onBack = { onBack(uiState.pairingStep) }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(18.dp)
            ) {
                SegmentedStepHeader(currentStep = uiState.pairingStep)
                Spacer(Modifier.height(18.dp))
                when (uiState.pairingStep) {
                    0 -> ScanStep(
                        devices = uiState.bleDevices,
                        selectedDevice = uiState.selectedBleDevice,
                        busy = uiState.pairingBusy,
                        message = uiState.pairingMessage,
                        onSelectBleDevice = onSelectBleDevice,
                        onNext = onGoToWifiStep
                    )
                    1 -> WifiStep(
                        selectedWifiSsid = uiState.selectedWifiSsid,
                        currentWifiSsid = uiState.currentWifiSsid,
                        networks = uiState.availableWifiNetworks,
                        selectedFrequencyMhz = uiState.selectedWifiFrequencyMhz,
                        routerPingOk = uiState.routerPingOk,
                        routerPingInProgress = uiState.routerPingInProgress,
                        busy = uiState.pairingBusy,
                        onRefreshWifiList = onRefreshWifiList,
                        onSelectWifiNetwork = onSelectWifiNetwork,
                        onConfirmWifi = onConfirmWifi
                    )
                    2 -> ProvisioningStep(message = uiState.pairingMessage)
                    else -> SuccessStep()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingTopBar(
    step: Int,
    onClose: () -> Unit,
    onBack: () -> Unit
) {
    TopAppBar(
        title = { Text("设备配对", fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = if (step == 0) onClose else onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
    )
}

@Composable
private fun SegmentedStepHeader(currentStep: Int) {
    val steps = listOf("扫描", "WiFi", "配网", "完成")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, label ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (index <= currentStep) ActiveBlue else StepInactive)
                )
                Spacer(Modifier.height(6.dp))
                Text(label, color = if (index == currentStep) ActiveBlue else Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ScanStep(
    devices: List<BleScanDevice>,
    selectedDevice: BleScanDevice?,
    busy: Boolean,
    message: String?,
    onSelectBleDevice: (BleScanDevice) -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        FrostedCard {
            Text("扫描 ESP32", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            Text(message ?: "正在扫描附近设备...", color = Muted)
            if (devices.isEmpty()) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("等待设备广播 BLE 配网服务", color = Muted)
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { device ->
                        val selected = selectedDevice?.macAddress == device.macAddress
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) SelectedSurface else CardInner)
                                .border(1.dp, if (selected) ActiveBlue else Line, RoundedCornerShape(18.dp))
                                .clickable { onSelectBleDevice(device) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, tint = ActiveBlue)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(device.name, fontWeight = FontWeight.Bold, color = Ink)
                                Text(device.macAddress, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            enabled = selectedDevice != null && !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("下一步")
        }
    }
}

@Composable
private fun WifiStep(
    selectedWifiSsid: String,
    currentWifiSsid: String,
    networks: List<WifiNetworkOption>,
    selectedFrequencyMhz: Int?,
    routerPingOk: Boolean?,
    routerPingInProgress: Boolean,
    busy: Boolean,
    onRefreshWifiList: () -> Unit,
    onSelectWifiNetwork: (String) -> Unit,
    onConfirmWifi: (String) -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        FrostedCard {
            Text("连接 WiFi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            Text("默认选择手机当前网络，也可以点开列表切换。", color = Muted)
            Spacer(Modifier.height(14.dp))
            WifiCompatibilityNotice(
                frequencyMhz = selectedFrequencyMhz,
                routerPingOk = routerPingOk,
                routerPingInProgress = routerPingInProgress
            )
            Spacer(Modifier.height(12.dp))
            WifiDropdown(
                selectedWifiSsid = selectedWifiSsid.ifBlank { currentWifiSsid },
                networks = networks,
                onRefreshWifiList = onRefreshWifiList,
                onSelectWifiNetwork = onSelectWifiNetwork
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("WiFi 密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onConfirmWifi(password) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(if (busy) "发送中..." else "确认")
        }
    }
}

@Composable
private fun WifiCompatibilityNotice(
    frequencyMhz: Int?,
    routerPingOk: Boolean?,
    routerPingInProgress: Boolean
) {
    val isFiveG = frequencyMhz != null && frequencyMhz in 4900..5900
    val isTwoPointFourG = frequencyMhz != null && frequencyMhz in 2400..2500

    when {
        isFiveG -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DangerRed.copy(alpha = 0.1f))
                    .border(1.dp, DangerRed.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(good = false, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("ESP32 不支持 5G 波段", color = DangerRed, fontWeight = FontWeight.Bold)
                    Text("请选择 2.4G WiFi 后再继续配网。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        isTwoPointFourG && routerPingOk == true -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GoodGreen.copy(alpha = 0.1f))
                    .border(1.dp, GoodGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(good = true, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("2.4G 网络可用", color = GoodGreen, fontWeight = FontWeight.Bold)
                    Text("已 ping 通路由器。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        isTwoPointFourG && routerPingInProgress -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在检查 2.4G 路由器连接...", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        isTwoPointFourG && routerPingOk == false -> {
            Text(
                "已识别为 2.4G 网络，但路由器未响应一次 ping；仍可尝试配网。",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WifiDropdown(
    selectedWifiSsid: String,
    networks: List<WifiNetworkOption>,
    onRefreshWifiList: () -> Unit,
    onSelectWifiNetwork: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = {
                onRefreshWifiList()
                expanded = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("当前选择网络", style = MaterialTheme.typography.labelMedium, color = Muted)
                Text(selectedWifiSsid.ifBlank { "未获取到 WiFi" }, color = Ink)
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            networks.forEach { network ->
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                append(network.ssid)
                                network.frequencyMhz?.let { frequency ->
                                    append(
                                        when {
                                            frequency in 2400..2500 -> "（2.4G）"
                                            frequency in 4900..5900 -> "（5G）"
                                            else -> "（${frequency}MHz）"
                                        }
                                    )
                                }
                                if (network.isCurrent) append("（当前）")
                            }
                        )
                    },
                    onClick = {
                        onSelectWifiNetwork(network.ssid)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ProvisioningStep(message: String?) {
    FrostedCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CircularProgressIndicator(modifier = Modifier.size(46.dp), strokeWidth = 4.dp)
            Spacer(Modifier.height(18.dp))
            Text("配网中...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(message ?: "等待 ESP32 连接 WiFi 并返回 IP", color = Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SuccessStep() {
    FrostedCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GoodGreen, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text("配网完成", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}
