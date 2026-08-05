# Project Checklist

This checklist tracks the requirements discussed during development and the current project direction.

## Current Direction: FSR402 + WiFi + Local MCP

- [x] ESP32-S3 reads FSR402 analog values from ADC-capable GPIO pins.
- [x] App receives ESP32 GPIO data over local WiFi.
- [x] App caches the latest 60 seconds of sensor data locally.
- [x] App exposes the cached data through a local MCP server.
- [x] Third-party AI chat apps can call MCP tools to read sensor data.

## ESP32 Firmware

- [x] GPIO `1-10` are available as FSR402 analog inputs.
- [x] ADC resolution is 12 bit, value range `0-4095`.
- [x] Sampling path supports `0.5s` App polling.
- [x] GPIO `11` is the reset button.
- [x] GPIO outside `1-12` are driven LOW as auxiliary ground pins where safe.
- [x] BLE provisioning service is available when WiFi is not configured or auto-connect fails.
- [x] BLE success response includes ESP32 IP and connected WiFi name.
- [x] BLE failure response reports password error, WiFi not found, and timeout.
- [x] WiFi auto-connect retries are limited to avoid long repeated tests.
- [x] mDNS advertises `esp32.local`.
- [x] Firmware source is included under `firmware/Esp32_wifi_connect/`.

## Android App: Pairing and Network

- [x] BLE scan filters ESP32 provisioning devices.
- [x] WiFi selection defaults to the phone's current WiFi.
- [x] WiFi list can be refreshed and changed before provisioning.
- [x] 5G WiFi warning is shown in the pairing UI.
- [x] 2.4G gateway ping result is shown before network selection.
- [x] Pairing errors are shown in Chinese for normal users.
- [x] If ESP32 joins WiFi but phone cannot reach it, App warns that phone and ESP32 may not be on the same WiFi.
- [x] Main-screen ESP32 warning updates or clears without restarting the App.
- [x] Stored paired devices can be deleted.

## Android App: FSR Sensor UI

- [x] Custom GPIO output / 74HC595 / LED control UI was removed from the current FSR direction.
- [x] Sensor configuration only supports analog input mode for GPIO `1-10`.
- [x] Each sensor can be named by the user.
- [x] Sensor cards show current value, delta, and short history chart.
- [x] Dark mode follows the system setting.
- [x] Visible author details remain in the bottom details area.
- [x] Hidden author metadata is included in the APK manifest.
- [x] App version is visible in the bottom details area.

## MCP Server

- [x] Foreground service keeps ESP32 polling and MCP support alive in the background.
- [x] MCP server address is visible on the main screen.
- [x] MCP address can be copied from the main screen.
- [x] MCP server can be enabled or disabled without stopping ESP32 polling.
- [x] Tool details are available in a separate page.
- [x] `fsr_list_sensors` lists configured sensors.
- [x] `fsr_get_snapshot` returns all current sensor values.
- [x] `fsr_get_sensor` returns one named/GPIO/keyed sensor.
- [x] `fsr_get_changes` returns changed values since the previous cursor.
- [x] `fsr_get_history` returns latest 60 seconds of local history.

## Release and Open Source

- [x] App version is `V2.3.23`.
- [x] Release APK is signed locally.
- [x] README explains project direction, usage, build steps, firmware, and MCP tools.
- [x] Source release excludes APKs, build cache, and signing keys.
- [x] License is BSD 2-Clause, requiring copyright/license retention on redistribution.
- [ ] Push source to GitHub repository after final verification.

