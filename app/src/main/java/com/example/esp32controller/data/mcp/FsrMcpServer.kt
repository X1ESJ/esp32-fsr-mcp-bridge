package com.example.esp32controller.data.mcp

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import com.example.esp32controller.model.FSR_ANALOG_MAX_VALUE
import com.example.esp32controller.model.FsrMcpSensor
import com.example.esp32controller.model.FsrMcpSnapshot
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.MCP_DEFAULT_PORT
import com.example.esp32controller.model.McpServerState
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MCP_PROTOCOL_VERSION = "2025-06-18"

class FsrMcpServer(
    private val context: Context,
    private val gson: Gson,
    private val snapshotProvider: () -> FsrMcpSnapshot,
    private val historyProvider: (
        names: Set<String>,
        fromMs: Long?,
        toMs: Long?,
        lastMs: Long?,
        intervalMs: Int?,
        compressionTolerance: Int
    ) -> Any,
    private val changesProvider: (
        cursor: Long?,
        names: Set<String>,
        minDelta: Int?
    ) -> Any,
    private val onSensorPost: (Map<String, Int>) -> Unit
) {
    private val _state = MutableStateFlow(McpServerState())
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    private var job: Job? = null
    private var serverSocket: ServerSocket? = null
    private var globalChangeCursor: Long = 0L

    fun start(scope: CoroutineScope, preferredPort: Int = MCP_DEFAULT_PORT) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val openedSocket = openServerSocket(preferredPort)
                serverSocket = openedSocket
                _state.value = McpServerState(
                    running = true,
                    host = resolveLocalIpAddress(),
                    port = openedSocket.localPort
                )

                coroutineScope {
                    while (isActive) {
                        val client = runCatching { openedSocket.accept() }.getOrNull() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(client)
                        }
                    }
                }
            } catch (exception: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    error = exception.message ?: "MCP 服务启动失败"
                )
            } finally {
                runCatching { serverSocket?.close() }
                serverSocket = null
                _state.value = _state.value.copy(running = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = _state.value.copy(running = false)
    }

    private fun openServerSocket(preferredPort: Int): ServerSocket {
        for (port in preferredPort..(preferredPort + 20)) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
                }
            }.getOrNull()
            if (socket != null) return socket
        }
        error("端口 $preferredPort-${preferredPort + 20} 都不可用")
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val request = readRequest(client) ?: return
            val response = when {
                request.method == "OPTIONS" -> HttpResponse(204, "text/plain", "")
                request.method == "GET" && request.path in setOf("/", "/health", "/mcp") -> handleHealth()
                request.method == "POST" && request.path in setOf("/hello", "/fsr", "/fsr/push") ->
                    handleSensorPush(request.body)
                request.method == "POST" && request.path == "/mcp" -> handleMcpRequest(request.body)
                else -> HttpResponse(404, "application/json", """{"ok":false,"error":"not_found"}""")
            }
            writeResponse(client, response)
        }
    }

    private fun handleHealth(): HttpResponse {
        val state = _state.value
        val payload = mapOf(
            "ok" to true,
            "name" to "ESP32 FSR MCP Server",
            "mcpEndpoint" to state.url,
            "pushEndpoint" to "http://${state.host}:${state.port}/hello",
            "tools" to listOf(
                "fsr_list_sensors",
                "fsr_get_snapshot",
                "fsr_get_sensor",
                "fsr_get_changes",
                "fsr_get_history"
            )
        )
        return HttpResponse(200, "application/json", gson.toJson(payload))
    }

    private fun handleSensorPush(body: String): HttpResponse {
        val values = runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            json.entrySet()
                .mapNotNull { (key, value) ->
                    value.asIntOrNull()?.let { key to it.coerceIn(0, FSR_ANALOG_MAX_VALUE) }
                }
                .toMap()
        }.getOrDefault(emptyMap())

        if (values.isEmpty()) {
            return HttpResponse(400, "application/json", """{"ok":false,"error":"no_numeric_sensor_value"}""")
        }

        onSensorPost(values)
        return HttpResponse(200, "application/json", gson.toJson(mapOf("ok" to true, "accepted" to values.size)))
    }

    private fun handleMcpRequest(body: String): HttpResponse {
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?: return HttpResponse(400, "application/json", rpcErrorBody(JsonNull.INSTANCE, -32700, "Parse error"))

        val responseElement = if (parsed.isJsonArray) {
            val responses = parsed.asJsonArray
                .mapNotNull { element -> handleRpc(element.takeIf { it.isJsonObject }?.asJsonObject) }
            if (responses.isEmpty()) return HttpResponse(202, "application/json", "")
            gson.toJsonTree(responses)
        } else {
            handleRpc(parsed.takeIf { it.isJsonObject }?.asJsonObject)
                ?: return HttpResponse(202, "application/json", "")
        }

        return HttpResponse(200, "application/json", gson.toJson(responseElement))
    }

    private fun handleRpc(request: JsonObject?): JsonObject? {
        if (request == null) return rpcError(JsonNull.INSTANCE, -32600, "Invalid Request")

        val hasId = request.has("id")
        val id = if (hasId) request.get("id") else JsonNull.INSTANCE
        val method = request.get("method")?.asString.orEmpty()
        if (method.isBlank()) return rpcError(id, -32600, "Invalid Request")
        if (!hasId && method.startsWith("notifications/")) return null

        return when (method) {
            "initialize" -> rpcResult(
                id,
                mapOf(
                    "protocolVersion" to (request.getAsJsonObject("params")
                        ?.get("protocolVersion")
                        ?.asString
                        ?: MCP_PROTOCOL_VERSION),
                    "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                    "serverInfo" to mapOf(
                        "name" to "esp32-fsr-phone-bridge",
                        "version" to "V2.3.23"
                    )
                )
            )
            "ping" -> rpcResult(id, emptyMap<String, Any>())
            "tools/list" -> rpcResult(id, mapOf("tools" to toolDefinitions()))
            "tools/call" -> handleToolCall(id, request.getAsJsonObject("params"))
            "resources/list" -> rpcResult(id, mapOf("resources" to emptyList<Any>()))
            "prompts/list" -> rpcResult(id, mapOf("prompts" to emptyList<Any>()))
            else -> rpcError(id, -32601, "Method not found")
        }
    }

    private fun handleToolCall(id: JsonElement, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.asString.orEmpty()
        val arguments = params?.getAsJsonObject("arguments") ?: JsonObject()
        val snapshot = snapshotProvider()

        val result = when (toolName) {
            "fsr_list_sensors" -> mapOf(
                "deviceOnline" to snapshot.deviceOnline,
                "resolutionBits" to snapshot.resolutionBits,
                "maxValue" to snapshot.maxValue,
                "sensors" to snapshot.sensors.map {
                    mapOf(
                        "name" to it.name,
                        "key" to it.key,
                        "pin" to it.pin,
                        "label" to it.label,
                        "source" to it.source
                    )
                }
            )
            "fsr_get_snapshot" -> snapshot
            "fsr_get_sensor" -> findSensor(snapshot, arguments)
            "fsr_get_changes" -> {
                val cursor = arguments.get("cursor")?.asLongOrNull() ?: globalChangeCursor
                val result = changesProvider(
                    cursor,
                    namesFromArguments(arguments),
                    arguments.get("min_delta")?.asIntOrNull()
                )
                val nextCursor = gson.toJsonTree(result).asJsonObject.get("nextCursor")?.asLongOrNull()
                if (!arguments.has("cursor") && nextCursor != null) {
                    globalChangeCursor = nextCursor
                }
                result
            }
            "fsr_get_history" -> historyProvider(
                namesFromArguments(arguments),
                arguments.get("fromMs")?.asLongOrNull(),
                arguments.get("toMs")?.asLongOrNull(),
                arguments.get("lastMs")?.asLongOrNull(),
                arguments.get("intervalMs")?.asIntOrNull(),
                arguments.get("compressionTolerance")?.asIntOrNull()?.coerceAtLeast(0) ?: 15
            )
            else -> return rpcError(id, -32602, "Unknown tool")
        }

        return rpcResult(
            id,
            mapOf(
                "content" to listOf(
                    mapOf(
                        "type" to "text",
                        "text" to gson.toJson(result)
                    )
                ),
                "structuredContent" to result,
                "isError" to false
            )
        )
    }

    private fun findSensor(snapshot: FsrMcpSnapshot, arguments: JsonObject): Any {
        val sensorName = arguments.get("name")?.asStringOrNull()
        val pin = arguments.get("pin")?.asIntOrNull()
        val key = arguments.get("key")?.asStringOrNull()
        val label = arguments.get("label")?.asStringOrNull()
        return snapshot.sensors.firstOrNull { sensor ->
            !sensorName.isNullOrBlank() && sensor.name.equals(sensorName, ignoreCase = true) ||
                pin != null && sensor.pin == pin ||
                !key.isNullOrBlank() && sensor.key.equals(key, ignoreCase = true) ||
                !label.isNullOrBlank() && sensor.label.equals(label, ignoreCase = true)
        } ?: mapOf("error" to "sensor_not_found")
    }

    private fun toolDefinitions(): List<Map<String, Any>> {
        val readOnly = mapOf("readOnlyHint" to true, "openWorldHint" to false)
        return listOf(
            mapOf(
                "name" to "fsr_list_sensors",
                "description" to "列出 App 当前知道的 FSR402 传感器和用户命名。",
                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_snapshot",
                "description" to "读取所有 FSR402 传感器的当前 12 位 ADC 值、变化量和更新时间。",
                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_sensor",
                "description" to "按用户命名、GPIO 或 key 读取单个 FSR402 传感器。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "App 里用户命名的传感器名称，例如 左耳"),
                        "pin" to mapOf("type" to "integer", "description" to "GPIO 编号，例如 4"),
                        "key" to mapOf("type" to "string", "description" to "内部 key，例如 gpio_4 或推送数据里的 hd"),
                        "label" to mapOf("type" to "string", "description" to "兼容旧字段，等同于 name")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_changes",
                "description" to "读取上一次调用之后发生变化的传感器事件，只返回变化数据。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "只看某个用户命名的传感器"),
                        "names" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "cursor" to mapOf("type" to "integer", "description" to "上一次返回的 nextCursor；不传则自动接着上次位置"),
                        "min_delta" to mapOf("type" to "integer", "description" to "最小变化量，默认 8，范围按 0-4095 计算")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_history",
                "description" to "读取最近 60 秒内 FSR402 传感器的本地历史缓存，包含抽样点和压缩稳定段。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "只看某个用户命名的传感器"),
                        "names" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "fromMs" to mapOf("type" to "integer", "description" to "起始时间戳，毫秒"),
                        "toMs" to mapOf("type" to "integer", "description" to "结束时间戳，毫秒，默认当前时间"),
                        "lastMs" to mapOf("type" to "integer", "description" to "最近多少毫秒，默认 60000"),
                        "intervalMs" to mapOf("type" to "integer", "description" to "返回点的抽样间隔，默认 500"),
                        "compressionTolerance" to mapOf("type" to "integer", "description" to "压缩稳定段的数值容差，默认 15")
                    )
                ),
                "annotations" to readOnly
            )
        )
    }

    private fun namesFromArguments(arguments: JsonObject): Set<String> {
        val names = linkedSetOf<String>()
        arguments.get("name")?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let(names::add)
        arguments.get("label")?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let(names::add)
        arguments.get("key")?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let(names::add)
        arguments.get("pin")?.asIntOrNull()?.let {
            names.add(it.toString())
            names.add("GPIO$it")
            names.add("gpio_$it")
        }
        arguments.get("names")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            element.asStringOrNull()?.takeIf { it.isNotBlank() }?.let(names::add)
        }
        return names
    }

    private fun rpcResult(id: JsonElement, result: Any): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", gson.toJsonTree(result))
        }
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
    }

    private fun rpcErrorBody(id: JsonElement, code: Int, message: String): String {
        return gson.toJson(rpcError(id, code, message))
    }

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var previous3 = -1
        var previous2 = -1
        var previous1 = -1

        while (headerBytes.size() < 32_768) {
            val current = input.read()
            if (current < 0) return null
            headerBytes.write(current)
            if (previous3 == '\r'.code && previous2 == '\n'.code &&
                previous1 == '\r'.code && current == '\n'.code
            ) {
                break
            }
            previous3 = previous2
            previous2 = previous1
            previous1 = current
        }

        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.split("\r\n").filter { it.isNotBlank() }
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null

        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else {
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
        }.toMap()

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(bodyBytes, read, contentLength - read)
            if (count < 0) break
            read += count
        }

        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1].substringBefore("?"),
            body = bodyBytes.copyOf(read).toString(StandardCharsets.UTF_8)
        )
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val output = socket.getOutputStream()
        val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "OK"
        }

        val headers = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: ${response.contentType}; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, MCP-Protocol-Version, Mcp-Session-Id\r\n")
            append("MCP-Protocol-Version: $MCP_PROTOCOL_VERSION\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    private fun JsonElement.asIntOrNull(): Int? {
        return runCatching { asInt }.getOrNull()
    }

    private fun JsonElement.asLongOrNull(): Long? {
        return runCatching { asLong }.getOrNull()
    }

    private fun JsonElement.asStringOrNull(): String? {
        return runCatching { asString }.getOrNull()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String
    )

    private data class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: String
    )

    @SuppressLint("DefaultLocale")
    private fun resolveLocalIpAddress(): String {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val linkProperties: LinkProperties? = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it) }
        val activeAddress = linkProperties?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
        if (!activeAddress.isNullOrBlank()) return activeAddress

        @Suppress("DEPRECATION")
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val raw = wifiManager?.connectionInfo?.ipAddress ?: return "127.0.0.1"
        return String.format(
            "%d.%d.%d.%d",
            raw and 0xFF,
            raw shr 8 and 0xFF,
            raw shr 16 and 0xFF,
            raw shr 24 and 0xFF
        )
    }
}

fun FsrSensorReading.toMcpSensor(nowMillis: Long): FsrMcpSensor {
    return FsrMcpSensor(
        key = key,
        pin = pin,
        name = name,
        value = value,
        previousValue = previousValue,
        delta = delta,
        absoluteDelta = abs(delta),
        normalized = normalized,
        percent = (normalized * 100f).roundToInt().coerceIn(0, 100),
        ageMillis = (nowMillis - updatedAtMillis).coerceAtLeast(0L),
        source = source
    )
}
