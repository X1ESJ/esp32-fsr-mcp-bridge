package com.example.esp32controller.data.fsr

import com.example.esp32controller.model.FsrBridgeSettings
import com.example.esp32controller.model.FsrLocalEvent
import com.example.esp32controller.model.FsrMinuteRollup
import com.example.esp32controller.model.FsrSensorReading
import com.example.esp32controller.model.FsrSessionSummary
import com.example.esp32controller.model.StoredDevice
import com.google.gson.Gson
import java.util.UUID
import java.util.Locale
import kotlin.math.roundToInt

private const val QUIET_SAMPLES_TO_ARM = 20
private const val POKE_MAX_MS = 400L
private const val HOLD_MIN_MS = 2_500L
private const val SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
private const val STROKE_WINDOW_MS = 2_500L

class FsrHistoryRecorder(
    private val database: FsrLocalDatabase,
    private val gson: Gson = Gson()
) {
    private val sensorStates = linkedMapOf<String, SensorRuntime>()
    private val recentShortEvents = ArrayDeque<FsrLocalEvent>()
    private var activeSession: SessionRuntime? = null
    private var minuteAccumulator: MinuteAccumulator? = null
    private var settings = FsrBridgeSettings()

    @Synchronized
    fun configure(nextSettings: FsrBridgeSettings) {
        settings = nextSettings
    }

    @Synchronized
    fun record(
        device: StoredDevice?,
        readings: List<FsrSensorReading>,
        now: Long
    ) {
        if (readings.isEmpty()) return
        database.insertSample(now, device, readings)

        val threshold = settings.triggerThreshold
        val activeReadings = readings.filter { reading ->
            val runtime = sensorStates.getOrPut(reading.key) { SensorRuntime(reading.name) }
            runtime.name = reading.name
            if (reading.value < threshold) {
                runtime.quietSamples = (runtime.quietSamples + 1).coerceAtMost(QUIET_SAMPLES_TO_ARM)
            }
            if (runtime.quietSamples >= QUIET_SAMPLES_TO_ARM) {
                runtime.ready = true
            }
            runtime.ready && reading.value >= threshold
        }

        val session = updateSession(activeReadings, now)
        updateMinuteRollup(device, readings, session?.id ?: "idle", now)
        updateSensorEvents(activeReadings, readings, session, now)
        flushIdleSessionIfNeeded(now)
    }

    @Synchronized
    fun flushCurrentMinute() {
        minuteAccumulator?.let { database.upsertMinuteRollup(it.toRollup(gson, settings.sampleIntervalMs)) }
    }

    @Synchronized
    fun resetRuntime() {
        sensorStates.clear()
        recentShortEvents.clear()
        activeSession = null
        minuteAccumulator = null
    }

    private fun updateSession(
        activeReadings: List<FsrSensorReading>,
        now: Long
    ): SessionRuntime? {
        val hasActive = activeReadings.isNotEmpty()
        if (hasActive && activeSession == null) {
            activeSession = SessionRuntime(
                id = "s-${now}-${UUID.randomUUID().toString().take(8)}",
                startMs = now,
                lastActiveMs = now
            )
        }

        val session = activeSession
        if (session != null && hasActive) {
            session.lastActiveMs = now
            session.sampleCount += 1
            val maxValue = activeReadings.maxOf { it.value }
            session.maxPressure = maxOf(session.maxPressure, maxValue)
            session.sumPressure += activeReadings.map { it.value }.average().roundToInt()
            session.activeSensors = activeReadings.map { it.name }.toSet()
            database.upsertSession(session.toSummary(now))
        } else if (session != null) {
            session.activeSensors = emptySet()
        }
        return activeSession
    }

    private fun updateMinuteRollup(
        device: StoredDevice?,
        readings: List<FsrSensorReading>,
        sessionId: String,
        now: Long
    ) {
        val minuteStart = now - now % 60_000L
        val current = minuteAccumulator
        if (current != null && current.minuteStartMs != minuteStart) {
            database.upsertMinuteRollup(current.toRollup(gson, settings.sampleIntervalMs))
            minuteAccumulator = null
        }

        val accumulator = minuteAccumulator ?: MinuteAccumulator(
            minuteStartMs = minuteStart,
            sessionId = sessionId,
            deviceMac = device?.macAddress,
            deviceName = device?.name
        ).also { minuteAccumulator = it }
        if (sessionId != "idle") {
            accumulator.sessionId = sessionId
        }
        accumulator.add(readings, settings.triggerThreshold)
    }

    private fun updateSensorEvents(
        activeReadings: List<FsrSensorReading>,
        allReadings: List<FsrSensorReading>,
        session: SessionRuntime?,
        now: Long
    ) {
        val activeKeys = activeReadings.map { it.key }.toSet()
        val activeCount = activeKeys.size

        allReadings.forEach { reading ->
            val runtime = sensorStates.getOrPut(reading.key) { SensorRuntime(reading.name) }
            val isActive = reading.key in activeKeys
            if (isActive) {
                if (runtime.activeStartMs == null) {
                    runtime.activeStartMs = now
                    runtime.peakValue = reading.value
                    runtime.sumValue = 0L
                    runtime.sampleCount = 0
                    runtime.maxSimultaneous = activeCount
                }
                runtime.peakValue = maxOf(runtime.peakValue, reading.value)
                runtime.sumValue += reading.value
                runtime.sampleCount += 1
                runtime.maxSimultaneous = maxOf(runtime.maxSimultaneous, activeCount)
            } else if (runtime.activeStartMs != null) {
                val event = runtime.finish(session?.id ?: activeSession?.id ?: "s-$now", now)
                if (event != null) {
                    database.insertEvent(event)
                    session?.applyEvent(event)
                    session?.let { database.upsertSession(it.toSummary(now)) }
                    maybeCreateStrokeEvent(event, session, now)
                }
            }
        }
    }

    private fun maybeCreateStrokeEvent(
        event: FsrLocalEvent,
        session: SessionRuntime?,
        now: Long
    ) {
        if (event.type !in setOf("戳", "按")) return
        recentShortEvents.addLast(event)
        while (recentShortEvents.firstOrNull()?.let { now - it.startMs > STROKE_WINDOW_MS } == true) {
            recentShortEvents.removeFirst()
        }

        val distinctSensors = recentShortEvents.flatMap { it.sensors }.distinct()
        if (recentShortEvents.size >= 3 && distinctSensors.size >= 3) {
            val start = recentShortEvents.first().startMs
            val end = recentShortEvents.last().endMs
            val stroke = FsrLocalEvent(
                sessionId = session?.id ?: event.sessionId,
                startMs = start,
                endMs = end,
                type = "抚摸",
                sensors = distinctSensors,
                peakValue = recentShortEvents.maxOf { it.peakValue },
                avgValue = recentShortEvents.map { it.avgValue }.average().roundToInt(),
                summary = "连续轻扫 ${distinctSensors.joinToString("、")}，持续 ${formatSeconds(end - start)} 秒"
            )
            database.insertEvent(stroke)
            session?.applyEvent(stroke)
            session?.let { database.upsertSession(it.toSummary(now)) }
            recentShortEvents.clear()
        }
    }

    private fun flushIdleSessionIfNeeded(now: Long) {
        val session = activeSession ?: return
        if (now - session.lastActiveMs >= SESSION_IDLE_TIMEOUT_MS) {
            database.upsertSession(session.toSummary(session.lastActiveMs, endAtLastActive = true))
            activeSession = null
            recentShortEvents.clear()
        }
    }

    private class SensorRuntime(
        var name: String
    ) {
        var ready: Boolean = false
        var quietSamples: Int = 0
        var activeStartMs: Long? = null
        var peakValue: Int = 0
        var sumValue: Long = 0L
        var sampleCount: Int = 0
        var maxSimultaneous: Int = 0

        fun finish(sessionId: String, now: Long): FsrLocalEvent? {
            val start = activeStartMs ?: return null
            val duration = now - start
            val avg = if (sampleCount == 0) peakValue else (sumValue.toDouble() / sampleCount).roundToInt()
            val type = when {
                maxSimultaneous >= 2 -> "捏"
                duration < POKE_MAX_MS -> "戳"
                duration > HOLD_MIN_MS -> "抱住不放"
                else -> "按"
            }
            val event = FsrLocalEvent(
                sessionId = sessionId,
                startMs = start,
                endMs = now,
                type = type,
                sensors = listOf(name),
                peakValue = peakValue,
                avgValue = avg,
                summary = "$name，$type，${formatSeconds(duration)} 秒"
            )
            activeStartMs = null
            peakValue = 0
            sumValue = 0L
            sampleCount = 0
            maxSimultaneous = 0
            return event
        }
    }

    private class SessionRuntime(
        val id: String,
        val startMs: Long,
        var lastActiveMs: Long
    ) {
        var sampleCount: Int = 0
        var sumPressure: Long = 0L
        var maxPressure: Int = 0
        var hugCount: Int = 0
        var pokeCount: Int = 0
        var pinchCount: Int = 0
        var strokeCount: Int = 0
        var pressCount: Int = 0
        var activeSensors: Set<String> = emptySet()

        fun applyEvent(event: FsrLocalEvent) {
            when (event.type) {
                "抱住不放" -> hugCount += 1
                "戳" -> pokeCount += 1
                "捏" -> pinchCount += 1
                "抚摸" -> strokeCount += 1
                else -> pressCount += 1
            }
            maxPressure = maxOf(maxPressure, event.peakValue)
        }

        fun toSummary(now: Long, endAtLastActive: Boolean = false): FsrSessionSummary {
            val end = if (endAtLastActive) lastActiveMs else maxOf(lastActiveMs, now)
            val duration = (end - startMs).coerceAtLeast(0L)
            val avg = if (sampleCount == 0) 0f else sumPressure.toFloat() / sampleCount.toFloat()
            val activeText = activeSensors.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "无"
            val summary = "持续 ${formatSeconds(duration)} 秒，当前接触：$activeText；峰值 $maxPressure；抱住 $hugCount 次，捏 $pinchCount 次，抚摸 $strokeCount 次，戳 $pokeCount 次，按 $pressCount 次"
            return FsrSessionSummary(
                id = id,
                startMs = startMs,
                endMs = end,
                durationMs = duration,
                avgPressure = avg,
                maxPressure = maxPressure,
                hugCount = hugCount,
                pokeCount = pokeCount,
                pinchCount = pinchCount,
                strokeCount = strokeCount,
                pressCount = pressCount,
                summary = summary,
                updatedAtMs = now
            )
        }
    }

    private class MinuteAccumulator(
        val minuteStartMs: Long,
        var sessionId: String,
        val deviceMac: String?,
        val deviceName: String?
    ) {
        private val sensorValues = linkedMapOf<String, SensorMinuteStats>()
        var sampleCount: Int = 0

        fun add(readings: List<FsrSensorReading>, threshold: Int) {
            sampleCount += 1
            readings.forEach { reading ->
                sensorValues.getOrPut(reading.name) { SensorMinuteStats() }
                    .add(reading.value, threshold)
            }
        }

        fun toRollup(gson: Gson, sampleIntervalMs: Long): FsrMinuteRollup {
            val rows = sensorValues.entries
                .sortedBy { it.key }
                .map { (name, stats) ->
                    listOf(
                        name,
                        stats.avg(),
                        stats.max,
                        stats.last,
                        (stats.activeSamples * sampleIntervalMs / 1000L).toInt()
                    )
                }
            val valuesJson = gson.toJson(
                mapOf(
                    "cols" to listOf("s", "avg", "max", "last", "actS"),
                    "data" to rows
                )
            )
            val top = sensorValues.entries.maxByOrNull { it.value.max }
            val summary = if (top == null) {
                "本分钟没有传感器数据"
            } else {
                "本分钟 $sampleCount 次采样，最高 ${top.key}=${top.value.max}"
            }
            return FsrMinuteRollup(
                remoteId = "${sessionId}_${minuteStartMs}",
                sessionId = sessionId,
                minuteStartMs = minuteStartMs,
                deviceMac = deviceMac,
                deviceName = deviceName,
                samples = sampleCount,
                valuesJson = valuesJson,
                summary = summary
            )
        }
    }

    private class SensorMinuteStats {
        var sum: Long = 0L
        var count: Int = 0
        var max: Int = 0
        var min: Int = Int.MAX_VALUE
        var last: Int = 0
        var activeSamples: Int = 0

        fun add(value: Int, threshold: Int) {
            sum += value
            count += 1
            max = maxOf(max, value)
            min = minOf(min, value)
            last = value
            if (value >= threshold) activeSamples += 1
        }

        fun avg(): Int {
            return if (count == 0) 0 else (sum.toDouble() / count.toDouble()).roundToInt()
        }
    }
}

private fun formatSeconds(durationMs: Long): String {
    return String.format(Locale.US, "%.2f", durationMs / 1000.0)
}
