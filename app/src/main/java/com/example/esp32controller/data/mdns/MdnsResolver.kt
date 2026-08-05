package com.example.esp32controller.data.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

class MdnsResolver(
    private val context: Context
) {
    suspend fun resolveEsp32Host(
        hostName: String = "esp32.local",
        timeoutMillis: Long = 60_000L
    ): String? = withContext(Dispatchers.IO) {
        val multicastLock = acquireMulticastLock()
        try {
            val direct = resolveByHostName(hostName)
            if (!direct.isNullOrBlank()) {
                return@withContext direct
            }

            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                val serviceType = if (remaining > timeoutMillis / 2) "_esp32._tcp." else "_http._tcp."
                val resolved = discoverWithNsd(serviceType = serviceType, timeoutMillis = 3500L)
                if (!resolved.isNullOrBlank()) {
                    return@withContext resolved
                }
                delay(500L)
            }
            null
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    private suspend fun resolveByHostName(hostName: String): String? {
        return withTimeoutOrNull(2500L) {
            runCatching {
                InetAddress.getByName(hostName).hostAddress
            }.getOrNull()?.takeIf { it.contains(".") }
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return runCatching {
            wifiManager.createMulticastLock("esp32-mdns-lock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private suspend fun discoverWithNsd(
        serviceType: String,
        timeoutMillis: Long
    ): String? {
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val nsdManager = context.getSystemService(NsdManager::class.java)
                if (nsdManager == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                var finished = false
                var resolving = false
                lateinit var discoveryListener: NsdManager.DiscoveryListener

                fun finish(ipAddress: String?) {
                    if (finished) return
                    finished = true
                    runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                    continuation.resume(ipAddress)
                }

                fun resolve(serviceInfo: NsdServiceInfo) {
                    if (resolving || finished) return
                    resolving = true
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                resolving = false
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val hostAddress = serviceInfo.host?.hostAddress
                                if (!hostAddress.isNullOrBlank()) {
                                    finish(hostAddress)
                                } else {
                                    resolving = false
                                }
                            }
                        }
                    )
                }

                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (serviceType == "_esp32._tcp.") {
                            resolve(serviceInfo)
                            return
                        }

                        val serviceName = serviceInfo.serviceName
                        val hostName = serviceInfo.host?.hostName.orEmpty()
                        if (
                            serviceName.contains("esp32", ignoreCase = true) ||
                            hostName.contains("esp32", ignoreCase = true)
                        ) {
                            resolve(serviceInfo)
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        finish(null)
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish(null)
                    }
                }

                try {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (_: Throwable) {
                    finish(null)
                }

                continuation.invokeOnCancellation {
                    if (!finished) {
                        finished = true
                        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                    }
                }
            }
        }
    }
}
