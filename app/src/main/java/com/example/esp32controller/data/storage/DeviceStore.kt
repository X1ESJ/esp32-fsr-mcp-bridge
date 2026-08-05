package com.example.esp32controller.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.esp32controller.model.StoredDevice
import com.example.esp32controller.model.StoredDevicesSnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATASTORE_NAME = "esp32_device_store"
private val Context.deviceDataStore by preferencesDataStore(name = DATASTORE_NAME)

class DeviceStore(
    private val context: Context,
    private val gson: Gson = Gson()
) {
    private val devicesKey = stringPreferencesKey("devices_json")
    private val selectedMacKey = stringPreferencesKey("selected_mac")

    val snapshotFlow: Flow<StoredDevicesSnapshot> = context.deviceDataStore.data.map { preferences ->
        val rawDevices = preferences[devicesKey].orEmpty()
        val type = object : TypeToken<List<StoredDevice>>() {}.type
        val devices = runCatching<List<StoredDevice>> {
            gson.fromJson(rawDevices, type) ?: emptyList()
        }.getOrDefault(emptyList())

        StoredDevicesSnapshot(
            devices = devices,
            selectedMacAddress = preferences[selectedMacKey]
        )
    }

    suspend fun upsertDevice(device: StoredDevice, selectAfterSave: Boolean = true) {
        context.deviceDataStore.edit { preferences ->
            val snapshot = readDevices(preferences[devicesKey].orEmpty()).toMutableList()
            val index = snapshot.indexOfFirst { it.macAddress == device.macAddress }
            if (index >= 0) {
                snapshot[index] = device
            } else {
                snapshot.add(device)
            }
            preferences[devicesKey] = gson.toJson(snapshot)
            if (selectAfterSave) {
                preferences[selectedMacKey] = device.macAddress
            }
        }
    }

    suspend fun updateSelectedDevice(macAddress: String) {
        context.deviceDataStore.edit { preferences ->
            preferences[selectedMacKey] = macAddress
        }
    }

    suspend fun updateDeviceIp(macAddress: String, ipAddress: String) {
        context.deviceDataStore.edit { preferences ->
            val snapshot = readDevices(preferences[devicesKey].orEmpty()).toMutableList()
            val index = snapshot.indexOfFirst { it.macAddress == macAddress }
            if (index >= 0) {
                snapshot[index] = snapshot[index].copy(ipAddress = ipAddress)
                preferences[devicesKey] = gson.toJson(snapshot)
            }
        }
    }

    suspend fun deleteDevice(macAddress: String) {
        context.deviceDataStore.edit { preferences ->
            val snapshot = readDevices(preferences[devicesKey].orEmpty())
                .filterNot { it.macAddress == macAddress }
            preferences[devicesKey] = gson.toJson(snapshot)

            if (preferences[selectedMacKey] == macAddress) {
                val nextMacAddress = snapshot.firstOrNull()?.macAddress
                if (nextMacAddress == null) {
                    preferences.remove(selectedMacKey)
                } else {
                    preferences[selectedMacKey] = nextMacAddress
                }
            }
        }
    }

    private fun readDevices(rawDevices: String): List<StoredDevice> {
        if (rawDevices.isBlank()) return emptyList()
        val type = object : TypeToken<List<StoredDevice>>() {}.type
        return runCatching<List<StoredDevice>> {
            gson.fromJson(rawDevices, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
