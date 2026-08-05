package com.example.esp32controller.data.mcp

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import com.example.esp32controller.BuildConfig
import com.example.esp32controller.model.FSR_ANALOG_MAX_VALUE
import com.example.esp32controller.model.FsrChangesResult
import com.example.esp32controller.model.FsrHistoryResult
import com.example.esp32controller.model.FsrLocalEvent
import com.example.esp32controller.model.FsrMcpSensor
import com.example.esp32controller.model.FsrMcpSnapshot
import com.example.esp32controller.model.FsrSessionSummary
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.FsrWindowResult
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
    ) -> FsrHistoryResult,
    private val changesProvider: (
        cursor: Long?,
        names: Set<String>,
        minDelta: Int?
    ) -> FsrChangesResult,
    private val sessionsProvider: (limit: Int, sinceMs: Long?) -> List<FsrSessionSummary>,
    private val eventsProvider: (
        fromMs: Long,
        toMs: Long,
        names: Set<String>,
        type: String?,
        sessionId: String?,
        limit: Int
    ) -> List<FsrLocalEvent>,
    private val windowProvider: (fromMs: Long, toMs: Long, limit: Int) -> FsrWindowResult,
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
                "fsr_get_history",
                "fsr_list_sessions",
                "fsr_get_session_summary",
                "fsr_get_events",
                "fsr_get_window"
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
                        "version" to BuildConfig.VERSION_NAME
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
                        "source" to it.source
                    )
                }
            )
            "fsr_get_snapshot" -> compactSnapshot(snapshot)
            "fsr_get_sensor" -> compactSensor(snapshot, arguments)
            "fsr_get_changes" -> {
                val cursor = arguments.get("cursor")?.asLongOrNull() ?: globalChangeCursor
                val changes = changesProvider(
                    cursor,
                    namesFromArguments(arguments),
                    arguments.get("min_delta")?.asIntOrNull()
                )
                if (!arguments.has("cursor")) {
                    globalChangeCursor = changes.nextCursor
                }
                compactChanges(changes)
            }
            "fsr_get_history" -> {
                val history = historyProvider(
                    namesFromArguments(arguments),
                    arguments.get("fromMs")?.asLongOrNull(),
                    arguments.get("toMs")?.asLongOrNull(),
                    arguments.get("lastMs")?.asLongOrNull(),
                    arguments.get("intervalMs")?.asIntOrNull(),
                    arguments.get("compressionTolerance")?.asIntOrNull()?.coerceAtLeast(0) ?: 15
                )
                compactHistory(history, arguments)
            }
            "fsr_list_sessions" -> compactSessions(
                sessionsProvider(
                    arguments.get("limit")?.asIntOrNull() ?: 12,
                    arguments.get("sinceMs")?.asLongOrNull()
                )
            )
            "fsr_get_session_summary" -> {
                val sessions = sessionsProvider(30, null)
                val requestedId = arguments.get("id")?.asStringOrNull()
                val session = if (requestedId.isNullOrBlank()) {
                    sessions.firstOrNull()
                } else {
                    sessions.firstOrNull { it.id == requestedId }
                }
                if (session == null) {
                    mapOf("error" to "session_not_found")
                } else {
                    val events = eventsProvider(
                        session.startMs,
                        session.endMs,
                        emptySet(),
                        null,
                        session.id,
                        arguments.get("limit")?.asIntOrNull() ?: 40
                    )
                    compactSessionSummary(session, events)
                }
            }
            "fsr_get_events" -> {
                val now = System.currentTimeMillis()
                val toMs = arguments.get("toMs")?.asLongOrNull() ?: now
                val fromMs = arguments.get("fromMs")?.asLongOrNull()
                    ?: (toMs - (arguments.get("lastMs")?.asLongOrNull() ?: 8 * 60 * 60 * 1000L))
                compactEvents(
                    fromMs = fromMs,
                    events = eventsProvider(
                        fromMs,
                        toMs,
                        namesFromArguments(arguments),
                        arguments.get("type")?.asStringOrNull(),
                        arguments.get("sessionId")?.asStringOrNull(),
                        arguments.get("limit")?.asIntOrNull() ?: 80
                    )
                )
            }
            "fsr_get_window" -> {
                val now = System.currentTimeMillis()
                val toMs = arguments.get("toMs")?.asLongOrNull() ?: now
                val fromMs = arguments.get("fromMs")?.asLongOrNull()
                    ?: (toMs - (arguments.get("lastMs")?.asLongOrNull() ?: 8 * 60 * 60 * 1000L))
                compactWindow(windowProvider(fromMs, toMs, arguments.get("limit")?.asIntOrNull() ?: 480))
            }
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
                )
            )
        )
    }

    private fun compactSnapshot(snapshot: FsrMcpSnapshot): Map<String, Any?> {
        return mapOf(
            "t" to snapshot.updatedAtMillis,
            "online" to snapshot.deviceOnline,
            "max" to snapshot.maxValue,
            "cols" to listOf("s", "v", "d", "ageMs"),
            "data" to snapshot.sensors.map { sensor ->
                listOf(sensor.name, sensor.value, sensor.delta, sensor.ageMillis)
            }
        )
    }

    private fun compactSensor(snapshot: FsrMcpSnapshot, arguments: JsonObject): Any {
        val sensor = findSensor(snapshot, arguments)
            ?: return mapOf("error" to "sensor_not_found")
        return mapOf(
            "s" to sensor.name,
            "v" to sensor.value,
            "d" to sensor.delta,
            "ageMs" to sensor.ageMillis
        )
    }

    private fun compactChanges(result: FsrChangesResult): Map<String, Any?> {
        val changes = result.changes
        val t0 = changes.firstOrNull()?.t
        val baseTime = t0 ?: 0L
        return mapOf(
            "cursor" to result.cursor,
            "next" to result.nextCursor,
            "minD" to result.minDelta,
            "t0" to t0,
            "cols" to listOf("s", "dt", "v", "d"),
            "data" to changes.map { change ->
                listOf(
                    change.name,
                    change.t - baseTime,
                    change.value,
                    change.delta
                )
            }
        )
    }

    private fun compactHistory(result: FsrHistoryResult, arguments: JsonObject): Map<String, Any?> {
        val rawMode = arguments.get("mode")?.asStringOrNull()?.equals("raw", ignoreCase = true) == true ||
            arguments.get("includeRaw")?.asBooleanOrFalse() == true
        val cols = if (rawMode) listOf("t", "v") else listOf("from", "to", "v")
        return mapOf(
            "t0" to result.fromMs,
            "to" to result.toMs,
            "max" to result.maxValue,
            "mode" to if (rawMode) "raw" else "segments",
            "cols" to cols,
            "series" to result.series.map { series ->
                mapOf(
                    "s" to series.name,
                    "data" to if (rawMode) {
                        series.data.map { point ->
                            listOf(point.t - result.fromMs, point.value)
                        }
                    } else {
                        series.compressed.map { segment ->
                            listOf(segment.fromMs - result.fromMs, segment.toMs - result.fromMs, segment.value)
                        }
                    }
                )
            }
        )
    }

    private fun compactSessions(sessions: List<FsrSessionSummary>): Map<String, Any?> {
        return mapOf(
            "cols" to listOf("id", "from", "to", "durS", "max", "summary"),
            "data" to sessions.map { session ->
                listOf(
                    session.id,
                    session.startMs,
                    session.endMs,
                    session.durationMs / 1000,
                    session.maxPressure,
                    session.summary
                )
            }
        )
    }

    private fun compactSessionSummary(
        session: FsrSessionSummary,
        events: List<FsrLocalEvent>
    ): Map<String, Any?> {
        return mapOf(
            "id" to session.id,
            "from" to session.startMs,
            "to" to session.endMs,
            "durS" to session.durationMs / 1000,
            "avg" to session.avgPressure.roundToInt(),
            "max" to session.maxPressure,
            "counts" to mapOf(
                "抱住不放" to session.hugCount,
                "捏" to session.pinchCount,
                "抚摸" to session.strokeCount,
                "戳" to session.pokeCount,
                "按" to session.pressCount
            ),
            "summary" to session.summary,
            "eventCols" to listOf("dt", "type", "s", "durMs", "peak"),
            "events" to events.map { event ->
                listOf(
                    event.startMs - session.startMs,
                    event.type,
                    event.sensors.joinToString("+"),
                    event.durationMs,
                    event.peakValue
                )
            }
        )
    }

    private fun compactEvents(fromMs: Long, events: List<FsrLocalEvent>): Map<String, Any?> {
        return mapOf(
            "t0" to fromMs,
            "cols" to listOf("dt", "type", "s", "durMs", "peak"),
            "data" to events.map { event ->
                listOf(
                    event.startMs - fromMs,
                    event.type,
                    event.sensors.joinToString("+"),
                    event.durationMs,
                    event.peakValue
                )
            }
        )
    }

    private fun compactWindow(window: FsrWindowResult): Map<String, Any?> {
        return mapOf(
            "t0" to window.fromMs,
            "to" to window.toMs,
            "mode" to window.mode,
            "cols" to window.cols,
            "data" to window.rows
        )
    }

    private fun findSensor(snapshot: FsrMcpSnapshot, arguments: JsonObject): FsrMcpSensor? {
        val sensorName = arguments.get("name")?.asStringOrNull()
        val pin = arguments.get("pin")?.asIntOrNull()
        val key = arguments.get("key")?.asStringOrNull()
        val label = arguments.get("label")?.asStringOrNull()
        return snapshot.sensors.firstOrNull { sensor ->
            !sensorName.isNullOrBlank() && sensor.name.equals(sensorName, ignoreCase = true) ||
                pin != null && sensor.pin == pin ||
                !key.isNullOrBlank() && sensor.key.equals(key, ignoreCase = true) ||
                !label.isNullOrBlank() && sensor.label.equals(label, ignoreCase = true)
        }
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
                "description" to "紧凑读取所有 FSR402 传感器的当前 12 位 ADC 值，返回列为 s/name、v/value、d/delta、ageMs。",
                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_sensor",
                "description" to "按用户命名、GPIO 或 key 紧凑读取单个 FSR402 传感器。",
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
                "description" to "紧凑读取上一次调用之后发生变化的传感器事件；data 按 cols=[s,dt,v,d] 返回。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "只看某个用户命名的传感器"),
                        "names" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "cursor" to mapOf("type" to "integer", "description" to "上一次返回的 next；不传则自动接着上次位置"),
                        "min_delta" to mapOf("type" to "integer", "description" to "最小变化量，默认 8，范围按 0-4095 计算")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_history",
                "description" to "紧凑读取 App 短期历史缓存；默认返回压缩段 data=[from,to,v]，mode=raw 时返回原始点 data=[t,v]。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "只看某个用户命名的传感器"),
                        "names" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "fromMs" to mapOf("type" to "integer", "description" to "起始时间戳，毫秒"),
                        "toMs" to mapOf("type" to "integer", "description" to "结束时间戳，毫秒，默认当前时间"),
                        "lastMs" to mapOf("type" to "integer", "description" to "最近多少毫秒，默认跟随 App 设置里的短期历史"),
                        "intervalMs" to mapOf("type" to "integer", "description" to "返回点的抽样间隔，默认跟随 App 设置里的保存频率"),
                        "compressionTolerance" to mapOf("type" to "integer", "description" to "压缩稳定段的数值容差，默认 15"),
                        "mode" to mapOf("type" to "string", "description" to "默认 segments；传 raw 返回原始点"),
                        "includeRaw" to mapOf("type" to "boolean", "description" to "true 等同于 mode=raw")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_list_sessions",
                "description" to "读取手机本地数据库里的最近触摸会话摘要，适合白天询问昨晚发生过什么。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "limit" to mapOf("type" to "integer", "description" to "最多返回多少条，默认 12"),
                        "sinceMs" to mapOf("type" to "integer", "description" to "只看这个时间戳之后的会话")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_session_summary",
                "description" to "读取单个会话的摘要和少量事件列表；不传 id 默认读取最近会话。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string", "description" to "fsr_list_sessions 返回的会话 id"),
                        "limit" to mapOf("type" to "integer", "description" to "最多带回多少条事件，默认 40")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_events",
                "description" to "按时间窗口读取已分类的触摸事件，返回列为 dt/type/s/durMs/peak。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "fromMs" to mapOf("type" to "integer", "description" to "开始时间戳，毫秒"),
                        "toMs" to mapOf("type" to "integer", "description" to "结束时间戳，毫秒"),
                        "lastMs" to mapOf("type" to "integer", "description" to "最近多少毫秒，默认 8 小时"),
                        "name" to mapOf("type" to "string", "description" to "只看某个传感器名称"),
                        "type" to mapOf("type" to "string", "description" to "只看某类事件，如 抱住不放/捏/抚摸/戳/按"),
                        "sessionId" to mapOf("type" to "string", "description" to "只看某个会话"),
                        "limit" to mapOf("type" to "integer", "description" to "最多返回多少条，默认 80")
                    )
                ),
                "annotations" to readOnly
            ),
            mapOf(
                "name" to "fsr_get_window",
                "description" to "读取长期数据库里的分钟级摘要窗口，适合低 token 查看整晚趋势。",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "fromMs" to mapOf("type" to "integer", "description" to "开始时间戳，毫秒"),
                        "toMs" to mapOf("type" to "integer", "description" to "结束时间戳，毫秒"),
                        "lastMs" to mapOf("type" to "integer", "description" to "最近多少毫秒，默认 8 小时"),
                        "limit" to mapOf("type" to "integer", "description" to "最多返回多少个分钟点，默认 480")
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

    private fun JsonElement.asBooleanOrFalse(): Boolean {
        return runCatching { asBoolean }.getOrDefault(false)
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
