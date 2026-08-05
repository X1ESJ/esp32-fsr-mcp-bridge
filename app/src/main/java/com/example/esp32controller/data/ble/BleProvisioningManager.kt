package com.example.esp32controller.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.esp32controller.model.BleScanDevice
import com.example.esp32controller.model.PairingResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val provisioningServiceUuid = UUID.fromString("0000FFFF-0000-1000-8000-00805F9B34FB")
private val writeCharacteristicUuid = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
private val notifyCharacteristicUuid = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
private const val TAG = "ESP32-BLE"

class BleProvisioningException(
    message: String,
    val returnStep: Int = 0
) : Exception(message)

data class BleProvisioningStatus(
    val status: String,
    val reason: String? = null,
    val ipAddress: String? = null,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val message: String? = null
)

class BleProvisioningManager(
    private val context: Context
) {
    fun scanDevices(): Flow<List<BleScanDevice>> = callbackFlow {
        scanReadinessError()?.let { message ->
            close(BleProvisioningException(message, 0))
            return@callbackFlow
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: run {
                close(BleProvisioningException("当前设备不支持蓝牙", 0))
                return@callbackFlow
            }

        val adapter = bluetoothManager.adapter
            ?: run {
                close(BleProvisioningException("当前设备不支持蓝牙", 0))
                return@callbackFlow
            }

        if (!adapter.isEnabled) {
            close(BleProvisioningException("请先开启手机蓝牙", 0))
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
            ?: run {
                close(BleProvisioningException("BLE 扫描器不可用，请关闭再打开蓝牙后重试", 0))
                return@callbackFlow
            }

        val discovered = linkedMapOf<String, BleScanDevice>()
        fun isProvisioningCandidate(result: ScanResult): Boolean {
            val serviceUuids = runCatching { result.scanRecord?.serviceUuids.orEmpty() }.getOrDefault(emptyList())
            val hasTargetUuid = serviceUuids.any { it.uuid == provisioningServiceUuid }
            val deviceName = runCatching {
                result.scanRecord?.deviceName ?: result.device?.name ?: ""
            }.getOrDefault("")
            val matchesName = deviceName.contains("ESP32", ignoreCase = true) ||
                deviceName.contains("Provision", ignoreCase = true)
            return hasTargetUuid || matchesName
        }

        fun handleScanResult(result: ScanResult) {
            if (!isProvisioningCandidate(result)) return
            val device = result.device ?: return
            val address = runCatching { device.address }.getOrNull() ?: return
            val name = runCatching { result.scanRecord?.deviceName }.getOrNull()
                ?: runCatching { device.name }.getOrNull()
                ?: "ESP32 Provision"
            discovered[address] = BleScanDevice(
                name = name,
                macAddress = address,
                device = device
            )
            trySend(discovered.values.sortedBy { it.name }).isSuccess
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                runCatching { handleScanResult(result) }
                    .onFailure { throwable ->
                        Log.w(TAG, "scan result ignored", throwable)
                        if (throwable is SecurityException) {
                            close(BleProvisioningException("缺少蓝牙扫描权限，请重新授权后再试", 0))
                        }
                    }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    runCatching { handleScanResult(result) }
                        .onFailure { Log.w(TAG, "batch scan result ignored", it) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleProvisioningException("BLE 扫描失败，错误码：$errorCode", 0))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // 不再只依赖系统层 UUID 过滤，避免某些 ESP32 广播只把 UUID 放进 scan response 时被漏掉。
            scanner.startScan(null, settings, callback)
        } catch (_: SecurityException) {
            close(BleProvisioningException("缺少蓝牙扫描权限，请重新授权后再试", 0))
            return@callbackFlow
        } catch (throwable: Throwable) {
            close(BleProvisioningException("BLE 扫描启动失败：${throwable.message ?: "请重启蓝牙后重试"}", 0))
            return@callbackFlow
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    fun scanStatusMessages(targetMacAddress: String? = null): Flow<String> = callbackFlow {
        scanReadinessError()?.let { message ->
            close(BleProvisioningException(message, 0))
            return@callbackFlow
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: run {
                close(BleProvisioningException("当前设备不支持蓝牙", 0))
                return@callbackFlow
            }
        val adapter = bluetoothManager.adapter
            ?: run {
                close(BleProvisioningException("当前设备不支持蓝牙", 0))
                return@callbackFlow
            }
        if (!adapter.isEnabled) {
            close(BleProvisioningException("请先打开手机蓝牙", 0))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
            ?: run {
                close(BleProvisioningException("BLE 扫描器不可用", 0))
                return@callbackFlow
            }

        val lastStatusReadAt = mutableMapOf<String, Long>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = runCatching { device.address }.getOrNull() ?: return
                if (!targetMacAddress.isNullOrBlank() && !address.equals(targetMacAddress, ignoreCase = true)) return
                if (!isProvisioningCandidateSafe(result)) return
                val now = System.currentTimeMillis()
                val lastReadAt = lastStatusReadAt[address] ?: 0L
                if (now - lastReadAt < 2500L) return
                lastStatusReadAt[address] = now

                launch {
                    val status = readProvisioningStatus(device)
                    val message = status?.message
                    if (!message.isNullOrBlank()) {
                        trySend(message).isSuccess
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleProvisioningException("BLE 状态扫描失败，错误码：$errorCode", 0))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (_: SecurityException) {
            close(BleProvisioningException("缺少蓝牙扫描权限，无法读取设备 WiFi 错误", 0))
            return@callbackFlow
        } catch (throwable: Throwable) {
            close(BleProvisioningException("BLE 状态扫描启动失败：${throwable.message ?: "请稍后重试"}", 0))
            return@callbackFlow
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun provision(
        device: BluetoothDevice,
        ssid: String,
        password: String
    ): PairingResult = withContext(Dispatchers.IO) {
        connectionReadinessError()?.let { message ->
            throw BleProvisioningException(message, 0)
        }
        attemptProvision(device, ssid, password)
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptProvision(
        device: BluetoothDevice,
        ssid: String,
        password: String,
        attempt: Int = 1
    ): PairingResult {
        return try {
            withTimeout(60_000L) {
                suspendCancellableCoroutine { continuation ->
                    var gatt: BluetoothGatt? = null
                    var activeNotifyCharacteristic: BluetoothGattCharacteristic? = null
                    var finished = false
                    var wifiPayloadWritten = false

                    fun closeGatt() {
                        runCatching { gatt?.disconnect() }
                        runCatching { gatt?.close() }
                    }

                    fun fail(message: String, step: Int) {
                        if (finished) return
                        finished = true
                        closeGatt()
                        continuation.resumeWithException(BleProvisioningException(message, step))
                    }

                    fun succeed(result: PairingResult) {
                        if (finished) return
                        finished = true
                        closeGatt()
                        continuation.resume(result)
                    }

                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                val message = when (status) {
                                    133 -> "BLE 连接异常，请稍后重试"
                                    147 -> "BLE 连接失败，设备忙或上次连接未释放，请等待 2 秒后重试"
                                    else -> "BLE 连接失败，状态码：$status"
                                }
                                fail(message, 0)
                                return
                            }

                            when (newState) {
                                BluetoothGatt.STATE_CONNECTED -> {
                                    runCatching {
                                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                    }
                                    val discoveryStarted = runCatching { gatt.discoverServices() }
                                        .getOrElse { throwable ->
                                            fail("无法发现 BLE 服务：${throwable.message ?: "系统拒绝"}", 0)
                                            return
                                        }
                                    if (!discoveryStarted) {
                                        fail("无法发现 BLE 服务", 0)
                                    }
                                }

                                BluetoothGatt.STATE_DISCONNECTED -> {
                                    val step = if (wifiPayloadWritten) 2 else 0
                                    fail("BLE 已断开，正在尝试通过 mDNS 确认设备入网", step)
                                }
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("BLE 服务发现失败", 0)
                                return
                            }

                            val service: BluetoothGattService = gatt.getService(provisioningServiceUuid)
                                ?: run {
                                    fail("未找到 ESP32 配网服务", 0)
                                    return
                                }

                            val notifyCharacteristic = service.getCharacteristic(notifyCharacteristicUuid)
                                ?: run {
                                    fail("未找到 ESP32 通知特征值", 0)
                                    return
                                }

                            val descriptor = notifyCharacteristic.getDescriptor(cccdUuid)
                                ?: run {
                                    fail("设备未开启通知描述符，请更新 ESP32 固件", 0)
                                    return
                                }

                            activeNotifyCharacteristic = notifyCharacteristic
                            val notificationEnabled = runCatching {
                                gatt.setCharacteristicNotification(notifyCharacteristic, true)
                            }.getOrDefault(false)
                            if (!notificationEnabled) {
                                fail("无法开启 ESP32 通知", 0)
                                return
                            }

                            if (!writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                                fail("通知订阅写入失败", 0)
                            }
                        }

                        override fun onDescriptorWrite(
                            gatt: BluetoothGatt,
                            descriptor: BluetoothGattDescriptor,
                            status: Int
                        ) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("通知订阅失败", 0)
                                return
                            }

                            val service = gatt.getService(provisioningServiceUuid)
                                ?: run {
                                    fail("未找到 ESP32 配网服务", 0)
                                    return
                                }

                            val writeCharacteristic = service.getCharacteristic(writeCharacteristicUuid)
                                ?: run {
                                    fail("未找到 WiFi 写入特征值", 0)
                                    return
                                }

                            val payload = JsonObject().apply {
                                addProperty("ssid", ssid)
                                addProperty("password", password)
                            }.toString().toByteArray(StandardCharsets.UTF_8)

                            val writeType = when {
                                writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            }
                            writeCharacteristic.writeType = writeType

                            if (!writeCharacteristicCompat(gatt, writeCharacteristic, payload)) {
                                fail("WiFi 信息发送失败", 1)
                            }
                        }

                        override fun onCharacteristicWrite(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int
                        ) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("WiFi 信息写入失败", 1)
                            } else {
                                wifiPayloadWritten = true
                                startFallbackReads(gatt, activeNotifyCharacteristic) { finished }
                            }
                        }

                        @Deprecated("Deprecated in API 33")
                        override fun onCharacteristicChanged(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic
                        ) {
                            handleCharacteristicValue(characteristic.value, succeed = ::succeed, fail = ::fail)
                        }

                        override fun onCharacteristicChanged(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            value: ByteArray
                        ) {
                            handleCharacteristicValue(value, succeed = ::succeed, fail = ::fail)
                        }

                        @Deprecated("Deprecated in API 33")
                        override fun onCharacteristicRead(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int
                        ) {
                            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == notifyCharacteristicUuid) {
                                handleCharacteristicValue(characteristic.value, succeed = ::succeed, fail = ::fail)
                            }
                        }

                        override fun onCharacteristicRead(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            value: ByteArray,
                            status: Int
                        ) {
                            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == notifyCharacteristicUuid) {
                                handleCharacteristicValue(value, succeed = ::succeed, fail = ::fail)
                            }
                        }
                    }

                    try {
                        gatt = device.connectGatt(
                            context,
                            false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE
                        ) ?: run {
                            fail("无法创建 BLE 连接", 0)
                            return@suspendCancellableCoroutine
                        }
                    } catch (_: SecurityException) {
                        fail("缺少蓝牙连接权限", 0)
                    } catch (throwable: Throwable) {
                        fail(throwable.message ?: "BLE 连接初始化失败", 0)
                    }

                    continuation.invokeOnCancellation {
                        if (!finished) {
                            finished = true
                            closeGatt()
                        }
                    }
                }
            }
        } catch (exception: TimeoutCancellationException) {
            throw BleProvisioningException("等待 ESP32 配网结果超时", 1)
        } catch (exception: BleProvisioningException) {
            val message = exception.message.orEmpty()
            if (attempt < 2 && (message.contains("状态码：147") || message.contains("设备忙"))) {
                delay(1800L)
                attemptProvision(device, ssid, password, attempt + 1)
            } else {
                throw exception
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readProvisioningStatus(device: BluetoothDevice): BleProvisioningStatus? {
        return withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(8_000L) {
                    suspendCancellableCoroutine { continuation ->
                        var gatt: BluetoothGatt? = null
                        var finished = false

                        fun closeGatt() {
                            runCatching { gatt?.disconnect() }
                            runCatching { gatt?.close() }
                        }

                        fun finish(status: BleProvisioningStatus?) {
                            if (finished) return
                            finished = true
                            closeGatt()
                            continuation.resume(status)
                        }

                        val callback = object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    finish(null)
                                    return
                                }
                                when (newState) {
                                    BluetoothGatt.STATE_CONNECTED -> {
                                        val discoveryStarted = runCatching { gatt.discoverServices() }.getOrDefault(false)
                                        if (!discoveryStarted) finish(null)
                                    }
                                    BluetoothGatt.STATE_DISCONNECTED -> finish(null)
                                }
                            }

                            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    finish(null)
                                    return
                                }
                                val characteristic = gatt.getService(provisioningServiceUuid)
                                    ?.getCharacteristic(notifyCharacteristicUuid)
                                if (characteristic == null) {
                                    finish(null)
                                    return
                                }
                                val readStarted = runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
                                if (!readStarted) finish(null)
                            }

                            @Deprecated("Deprecated in API 33")
                            override fun onCharacteristicRead(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                status: Int
                            ) {
                                if (characteristic.uuid == notifyCharacteristicUuid && status == BluetoothGatt.GATT_SUCCESS) {
                                    finish(parseProvisioningStatus(characteristic.value))
                                } else {
                                    finish(null)
                                }
                            }

                            override fun onCharacteristicRead(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                value: ByteArray,
                                status: Int
                            ) {
                                if (characteristic.uuid == notifyCharacteristicUuid && status == BluetoothGatt.GATT_SUCCESS) {
                                    finish(parseProvisioningStatus(value))
                                } else {
                                    finish(null)
                                }
                            }
                        }

                        try {
                            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                                ?: run {
                                    finish(null)
                                    return@suspendCancellableCoroutine
                                }
                        } catch (_: SecurityException) {
                            finish(null)
                        } catch (_: Throwable) {
                            finish(null)
                        }

                        continuation.invokeOnCancellation {
                            if (!finished) {
                                finished = true
                                closeGatt()
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }

    private fun handleCharacteristicValue(
        raw: ByteArray?,
        succeed: (PairingResult) -> Unit,
        fail: (String, Int) -> Unit
    ) {
        val response = raw?.toString(StandardCharsets.UTF_8).orEmpty().trim()
        Log.d(TAG, "raw response: '$response'")
        if (response.isBlank()) {
            return
        }

        val jsonText = extractJsonObject(response)
            ?: run {
                Log.w(TAG, "ignored non-json response: '$response'")
                return
            }

        val json = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull()
            ?: run {
                Log.w(TAG, "ignored invalid json response: '$jsonText'")
                return
            }

        val status = json.get("status")?.asString?.trim().orEmpty()
        when {
            status.equals("ready", ignoreCase = true) -> Unit
            status.equals("ok", ignoreCase = true) -> {
                val ipAddress = json.get("ip")?.asString.orEmpty()
                val wifiSsid = runCatching { json.get("ssid")?.asString }.getOrNull()
                if (ipAddress.isBlank()) {
                    fail("ESP32 未返回 IP 地址", 2)
                } else {
                    succeed(PairingResult(ipAddress = ipAddress, wifiSsid = wifiSsid))
                }
            }

            status.equals("fail", ignoreCase = true) -> {
                val reason = json.get("reason")?.asString.orEmpty()
                fail(displayWifiReason(reason), 1)
            }

            else -> Unit
        }
    }

    private fun parseProvisioningStatus(raw: ByteArray?): BleProvisioningStatus? {
        val response = raw?.toString(StandardCharsets.UTF_8).orEmpty().trim()
        if (response.isBlank()) return null
        val jsonText = extractJsonObject(response) ?: return null
        val json = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull() ?: return null
        val status = runCatching { json.get("status")?.asString?.trim() }.getOrNull().orEmpty()
        val reason = runCatching { json.get("reason")?.asString }.getOrNull()
        val attempt = runCatching { json.get("attempt")?.asInt }.getOrNull()
        val maxAttempts = runCatching { json.get("maxAttempts")?.asInt }.getOrNull()
        val ipAddress = runCatching { json.get("ip")?.asString }.getOrNull()
        val message = when {
            status.equals("fail", ignoreCase = true) -> {
                val suffix = if (attempt != null && maxAttempts != null) {
                    "（已尝试 $attempt/$maxAttempts 次）"
                } else {
                    ""
                }
                "ESP32 WiFi 连接失败：${displayWifiReason(reason.orEmpty())}$suffix"
            }
            else -> null
        }
        return BleProvisioningStatus(
            status = status,
            reason = reason,
            ipAddress = ipAddress,
            attempt = attempt,
            maxAttempts = maxAttempts,
            message = message
        )
    }

    private fun displayWifiReason(reason: String): String {
        return when {
            reason.contains("ssid_empty", ignoreCase = true) -> "WiFi 名称为空"
            reason.contains("ssid_not_found", ignoreCase = true) -> "找不到该 WiFi，请确认名称或距离"
            reason.contains("找不到") -> "找不到该 WiFi，请确认名称或距离"
            reason.contains("no_ap", ignoreCase = true) -> "找不到该 WiFi，请确认名称或距离"
            reason.contains("ap_not_found", ignoreCase = true) -> "找不到该 WiFi，请确认名称或距离"
            reason.contains("password", ignoreCase = true) -> "WiFi 密码错误"
            reason.contains("auth", ignoreCase = true) -> "WiFi 密码错误"
            reason.contains("timeout", ignoreCase = true) || reason.contains("超时") -> "WiFi 连接超时"
            else -> reason.ifBlank { "ESP32 配网失败" }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFallbackReads(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        isFinished: () -> Boolean
    ) {
        if (characteristic == null) return
        Thread {
            repeat(50) {
                if (isFinished()) return@Thread
                Thread.sleep(1000L)
                if (isFinished()) return@Thread
                runCatching { gatt.readCharacteristic(characteristic) }
            }
        }.start()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun isProvisioningCandidateSafe(result: ScanResult): Boolean {
        val serviceUuids = runCatching { result.scanRecord?.serviceUuids.orEmpty() }.getOrDefault(emptyList())
        val hasTargetUuid = serviceUuids.any { it.uuid == provisioningServiceUuid }
        val deviceName = runCatching {
            result.scanRecord?.deviceName ?: result.device?.name ?: ""
        }.getOrDefault("")
        val matchesName = deviceName.contains("ESP32", ignoreCase = true) ||
            deviceName.contains("Provision", ignoreCase = true)
        return hasTargetUuid || matchesName
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, characteristic.writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    private fun scanReadinessError(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return "当前设备不支持 BLE"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return "缺少附近设备/蓝牙扫描权限"
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return "缺少蓝牙连接权限"
        } else {
            val hasLocationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!hasLocationPermission) return "缺少定位权限，旧版安卓/HarmonyOS 扫描 BLE 需要定位权限"
            if (!isLocationEnabled()) return "请打开手机定位服务，旧版安卓/HarmonyOS 关闭定位时可能无法扫描 BLE"
        }
        return null
    }

    private fun connectionReadinessError(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            return "缺少蓝牙连接权限，请重新授权后再配对"
        }
        return null
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
    }
}
