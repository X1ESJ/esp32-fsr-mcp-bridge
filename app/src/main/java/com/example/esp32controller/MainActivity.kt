package com.example.esp32controller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.esp32controller.data.fsr.FsrDataHub
import com.example.esp32controller.service.FsrBridgeService
import com.example.esp32controller.ui.MainScreen
import com.example.esp32controller.ui.theme.Esp32ControllerTheme
import com.example.esp32controller.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FsrDataHub.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            Esp32ControllerTheme {
                MainRoute(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun MainRoute(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }
    val requestedPermissions = remember {
        buildList {
            addAll(requiredPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var hasPermissions by remember { mutableStateOf(requiredPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = requiredPermissions.all { permission ->
            result[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermissions) {
            viewModel.refreshWifiContext()
        }
    }

    LaunchedEffect(Unit) {
        val shouldRequestPermissions = requestedPermissions.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (shouldRequestPermissions) {
            permissionLauncher.launch(requestedPermissions.toTypedArray())
        }
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            runCatching { FsrBridgeService.start(context) }
        }
    }

    MainScreen(
        uiState = uiState,
        hasPermissions = hasPermissions,
        onRequestPermissions = {
            permissionLauncher.launch(requestedPermissions.toTypedArray())
        },
        onOpenPairing = {
            if (hasPermissions) {
                viewModel.openPairingFlow()
            } else {
                permissionLauncher.launch(requestedPermissions.toTypedArray())
            }
        },
        onSelectDevice = viewModel::selectDevice,
        onDeleteDevice = viewModel::deleteStoredDevice,
        onOpenSensorPanel = viewModel::openSensorPanel,
        onCloseSensorPanel = viewModel::closeSensorPanel,
        onOpenSettings = viewModel::openSettings,
        onCloseSettings = viewModel::closeSettings,
        onUpdateHistoryWindow = viewModel::updateHistoryWindowMs,
        onUpdateSampleInterval = viewModel::updateSampleIntervalMs,
        onUpdateTriggerThreshold = viewModel::updateTriggerThreshold,
        onUpdateSupabaseSettings = viewModel::updateSupabaseSettings,
        onExportDatabase = viewModel::exportFsrDatabase,
        onSaveFsrSensor = viewModel::saveFsrSensor,
        onDeleteSensor = viewModel::deleteSensor,
        onRetryMdns = viewModel::retryMdns,
        onClearMessage = viewModel::clearTransientMessage,
        onClosePairing = viewModel::closePairingFlow,
        onSelectBleDevice = viewModel::selectBleDevice,
        onGoToWifiStep = viewModel::goToWifiStep,
        onPairingBack = viewModel::backFromStep,
        onConfirmWifi = viewModel::confirmWifiPassword,
        onRefreshWifiList = viewModel::refreshWifiContext,
        onSelectWifiNetwork = viewModel::selectWifiNetwork,
        onSetMcpEnabled = { enabled ->
            FsrBridgeService.setMcpEnabled(context, enabled)
        },
        onExitApp = {
            (context as? ComponentActivity)?.finish()
        }
    )
}
