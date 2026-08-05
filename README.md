# ESP32 FSR MCP Bridge

Copyright (c) 2026 LINYUN XESJ

All rights reserved.

Licensed under BSD 2-Clause License.

当前版本：`V2.3.23`

作者：琳云 XESJ

邮箱：xianeshijie@outlook.com

本项目通过 WiFi 接收 ESP32-S3 支持 ADC 的 GPIO 端口数据，在 Android 手机本地缓存最近 60 秒，并通过手机本地 MCP 服务让第三方 AI 聊天应用读取这些传感器数据。

灵感来源：群友想要制作“共感娃娃”，本人提供 WiFi 和 BLE 连接思路，最后借助 AI 写出本项目。

## 目录 Table of Contents

- [1. 快速使用教程](#1-快速使用教程)
- [2. 项目链路](#2-项目链路)
- [3. 硬件接线指南](#3-硬件接线指南)
- [4. ESP32 固件](#4-esp32-固件)
- [5. Android App](#5-android-app)
- [6. MCP 服务和 Tools](#6-mcp-服务和-tools)
- [7. HTTP API](#7-http-api)
- [8. 构建和发布](#8-构建和发布)
- [9. 常见故障与解决方案](#9-常见故障与解决方案)
- [10. 安全说明](#10-安全说明)
- [11. 已知局限和后续方向](#11-已知局限和后续方向)
- [12. 更新日志 Changelog](#12-更新日志-changelog)
- [13. 开源协议](#13-开源协议)

## 1. 快速使用教程

### 1.1 硬件接线

1. 将 FSR402 薄膜压力传感器接成分压电路。
2. 将分压点接到 ESP32-S3 的 GPIO `1-10` 中任意一个。
3. GPIO `11` 接复位按钮，长按 5 秒用于清除 WiFi 和传感器配置。
4. GPIO `12` 固件默认拉低，可作为预留辅助地，不作为传感器输入。

### 1.2 ESP32 烧录

1. 使用 Arduino IDE 或 Arduino CLI 打开 `firmware/Esp32_wifi_connect/Esp32_wifi_connect.ino`。
2. 开发板选择 ESP32-S3。
3. 编译并烧录固件。
4. 打开串口监视器，波特率设置为 `115200`。
5. 首次启动没有 WiFi 配置时，ESP32 会自动进入 BLE 配网模式。

### 1.3 Android 安装配网

1. 安装 Release 里的 APK。
2. 打开 App，授予蓝牙、定位、通知、WiFi 状态和附近设备相关权限。
3. 点击主界面右上角“配对”。
4. 选择扫描到的 ESP32。
5. 选择 `2.4G WiFi`，输入密码并确认。
6. 配网完成后进入传感器配置页，为每个 GPIO 添加名称，例如“左耳”“身体”“右前肢”。
7. 回到主界面确认 ESP32 在线，并确认 MCP 服务已开启。

### 1.4 AI 客户端接入

1. 在 App 主界面复制 MCP 地址，格式通常为 `http://手机局域网IP:9333/mcp`。
2. 在支持 HTTP MCP 的第三方 AI 聊天应用中添加该地址。
3. 让 AI 调用 `fsr_get_snapshot`、`fsr_get_changes` 或 `fsr_get_history` 获取传感器数据。

如果 ESP32 已经连上 WiFi，但手机无法通过 WiFi 访问设备，App 会提示 ESP32 当前连接的 WiFi 名称。此时通常是手机自动切到了其他 WiFi 或移动网络，需要把手机切回同一局域网。

## 2. 项目链路

```text
FSR402 薄膜压力传感器
    -> ESP32-S3 ADC GPIO 1-10
    -> ESP32 HTTP API / mDNS / BLE 错误回传
    -> Android App 本地缓存最近 60 秒
    -> 手机本地 MCP Server
    -> 第三方 AI 聊天应用调用 MCP tools
```

这个项目的重点不是普通“遥控开关”，而是把多个传感器的实时模拟值变成 AI 应用可查询、可分析、可持续观察的数据源。

## 3. 硬件接线指南

### 3.1 FSR402 标准分压电路

推荐接法：

```text
3.3V
  |
 FSR402
  |
  +---- ESP32-S3 ADC GPIO 1-10
  |
 10k ohm 电阻
  |
 GND
```

说明：

- FSR402 一端接 `3.3V`。
- FSR402 另一端接分压点。
- 分压点同时接 `10k ohm` 电阻到 `GND`。
- 分压点接入 ESP32-S3 的 GPIO `1-10`。
- 禁止把 `5V` 直接接入 ADC 引脚，否则可能烧毁 IO。

### 3.2 GPIO 定义

| GPIO | 用途 |
| --- | --- |
| `1-10` | FSR402 模拟采集输入，12 位 ADC，范围 `0-4095` |
| `11` | 长按 5 秒清除 WiFi 和传感器配置 |
| `12` | 预留闲置，固件默认拉低，可作为传感器共地辅助 |
| 其他安全可用 GPIO | 固件默认拉低，可作为辅助负极使用 |

### 3.3 供电建议

- ESP32-S3 推荐使用稳定的 `5V/1A` 供电。
- 多个 FSR402 同时接入时，建议使用可靠的 GND 汇流连接，避免线材过细导致读数跳变。
- 传感器线较长时，读数可能被干扰，可以缩短线材、整理地线，或在软件侧调高变化阈值。

## 4. ESP32 固件

固件文件：

```text
firmware/Esp32_wifi_connect/Esp32_wifi_connect.ino
```

依赖：

- ESP32 Arduino Core，建议 `2.0.14+` 或 `3.x`。
- `WiFi.h`，ESP32 Core 内置。
- `WebServer.h`，ESP32 Core 内置。
- `ESPmDNS.h`，ESP32 Core 内置。
- `ESP32 BLE Arduino`，ESP32 Core 内置 BLE 组件。
- `ArduinoJson`，需要在 Arduino Library Manager 中安装。

串口调试：

- 波特率：`115200`。
- 串口会输出 BLE 配网、WiFi 重试、WiFi 失败原因、HTTP 服务启动、mDNS 启动和传感器配置日志。

BLE 配网协议：

- Service UUID：`0000FFFF-0000-1000-8000-00805F9B34FB`
- App 写入 Characteristic：`0000FF01-0000-1000-8000-00805F9B34FB`
- ESP32 回传 Characteristic：`0000FF02-0000-1000-8000-00805F9B34FB`

BLE 写入数据：

```json
{"ssid":"WiFi 名称","password":"WiFi 密码"}
```

成功回包：

```json
{"status":"ok","ip":"192.168.1.xxx","ssid":"ESP32 连接的 WiFi 名称"}
```

失败回包示例：

```json
{"status":"fail","reason":"ssid_not_found","attempt":2,"maxAttempts":2}
```

WiFi 连接行为：

- ESP32 最多执行 `2` 轮 WiFi 连接流程。
- 每一轮会交给 WiFi 模块自行完成底层扫描和握手，所以串口中可能看到多条状态变化。
- 2 轮仍失败时，ESP32 会通过 BLE 回传失败原因并重新进入 BLE 配网。
- App 会把 `ssid_not_found` 翻译成“找不到该 WiFi，请确认名称或距离”。

复位行为：

- GPIO `11` 长按 5 秒：清空保存的 WiFi SSID、WiFi 密码和传感器 GPIO 配置，然后重启进入 BLE 配网模式。
- 短按 GPIO `11` 不会清除配置。

## 5. Android App

技术栈：

- Kotlin
- Jetpack Compose
- Android BluetoothLeScanner / BluetoothGatt
- Retrofit + OkHttp，网络超时 `2s`
- Kotlin Coroutines
- StateFlow
- DataStore
- Android NsdManager
- Foreground Service

系统建议：

- 工程 `minSdk` 为 `26`，建议 Android 13 及以上使用。
- Android 12 及以上需要蓝牙扫描和蓝牙连接权限。
- Android 13 及以上需要通知权限，前台服务常驻通知依赖该权限。
- mDNS 自动发现依赖局域网和多播能力，App 已声明 `CHANGE_WIFI_MULTICAST_STATE`。

后台运行：

- App 使用前台 Service 和常驻通知保持后台采集。
- 华为、OPPO、vivo、小米等系统可能会限制后台服务，建议手动允许后台运行、自启动和通知。
- 如果 MCP 工具长时间没有数据，先打开 App 主界面确认前台服务和 ESP32 在线状态。

主要页面：

- 主界面：设备状态、ESP32 报错、MCP 状态、本机 MCP 地址、传感器概览。
- 配网页面：BLE 扫描、WiFi 选择、密码输入、配网进度、完成提示。
- 传感器配置页：添加 GPIO `1-10` 的模拟输入，并为每个传感器命名。
- MCP 工具详情页：展示每个 MCP tool 的名称、功能、传入值和返回值。

## 6. MCP 服务和 Tools

默认 MCP 地址：

```text
http://手机局域网IP:9333/mcp
```

安全边界：

- MCP 服务只设计给同一局域网内使用。
- 默认端口：`9333`。
- 当前没有身份验证和加密，不要映射到公网。
- 建议同一时间只连接一个 AI 客户端，多客户端同时高频调用会排队。

HTTP MCP 客户端配置示例，字段名以客户端实际版本为准：

```json
{
  "mcpServers": {
    "esp32-fsr-mcp-bridge": {
      "type": "http",
      "url": "http://192.168.1.23:9333/mcp"
    }
  }
}
```

### 6.1 `fsr_list_sensors`

功能：列出 App 当前配置的全部 FSR402 传感器。

传入值：

```json
{}
```

返回示例：

```json
{
  "deviceOnline": true,
  "resolutionBits": 12,
  "maxValue": 4095,
  "sensors": [
    {"name": "左耳", "pin": 1, "key": "gpio_1", "source": "ESP32"}
  ]
}
```

### 6.2 `fsr_get_snapshot`

功能：获取全部传感器的实时快照。

传入值：

```json
{}
```

返回示例：

```json
{
  "deviceName": "ESP32-Provision",
  "deviceIp": "192.168.1.13",
  "deviceOnline": true,
  "updatedAtMillis": 1785930000000,
  "sensors": [
    {
      "name": "左耳",
      "pin": 1,
      "key": "gpio_1",
      "value": 2300,
      "previousValue": 2250,
      "delta": 50,
      "percent": 56
    }
  ]
}
```

### 6.3 `fsr_get_sensor`

功能：按用户命名、GPIO 或 key 获取单个传感器。

传入值：

```json
{"name":"左耳"}
```

也可以使用：

```json
{"pin":1}
```

找不到时返回：

```json
{"error":"sensor_not_found"}
```

### 6.4 `fsr_get_changes`

功能：获取上一次调用之后发生变化的数据，只返回变化项。

传入值：

```json
{
  "cursor": 12,
  "names": ["左耳", "身体"],
  "min_delta": 8
}
```

返回示例：

```json
{
  "cursor": 12,
  "nextCursor": 15,
  "minDelta": 8,
  "changes": [
    {
      "sequence": 13,
      "name": "左耳",
      "pin": 1,
      "value": 2300,
      "previousValue": 2250,
      "delta": 50,
      "absoluteDelta": 50
    }
  ]
}
```

### 6.5 `fsr_get_history`

功能：获取最近 60 秒本地历史缓存，包含抽样点和压缩稳定段。

传入值：

```json
{
  "names": ["左耳"],
  "lastMs": 60000,
  "intervalMs": 500,
  "compressionTolerance": 15
}
```

规则：

- App 固定每 `0.5s` 采集一次，60 秒最多约 `120` 帧。
- 10 个传感器同时启用时，最多约 `1200` 个原始点。
- 默认压缩容差为 `±15` 个 ADC 值。
- 连续稳定数据会合并为区间，减少 AI 客户端读取体积。

AI Prompt 示例：

```text
请调用 fsr_get_history，读取“左耳”和“身体”最近 60 秒数据，
判断是否出现持续按压、短促点击或逐渐加重的压力变化。
```

## 7. HTTP API

ESP32 成功接入 WiFi 后提供 HTTP API，端口为 `80`。

读取接口：

- `GET /pins`：返回可用 GPIO、当前 IP、当前 WiFi 名称和已配置传感器。
- `GET /snapshot`：返回完整传感器快照。
- `GET /fsr/changes`：返回最近变化的传感器数据。
- `GET /status`：兼容旧版状态接口。

写入接口：

- `POST /pin/config`：保存某个 GPIO 的 FSR 输入配置。
- `POST /pin/delete`：删除某个 GPIO 的配置。

`POST /pin/config` 请求体示例，`application/x-www-form-urlencoded`：

```text
pin=1&direction=input&mode=analog&value=0&label=左耳
```

`POST /pin/delete` 请求体示例：

```text
pin=1
```

固件保留旧版 `GET /pin/config` 和 `GET /pin/delete` 兼容，但新版本 App 使用 `POST`。

## 8. 构建和发布

### 8.1 仓库结构

```text
.
├── app/                         # Android App 源码
├── firmware/Esp32_wifi_connect/ # ESP32 Arduino 固件
├── gradle/                      # Gradle Wrapper
├── LICENSE                      # BSD 2-Clause License
├── README.md                    # 项目说明
└── release.properties.example   # Release 签名配置示例
```

### 8.2 构建 App

调试版：

```powershell
.\gradlew.bat assembleDebug
```

正式版：

```powershell
.\gradlew.bat assembleRelease
```

签名配置：

1. 创建 `local-signing/`。
2. 复制 `release.properties.example` 为 `local-signing/release.properties`。
3. 填入本地 keystore 路径和密码。
4. 执行 `.\gradlew.bat assembleRelease`。

`local-signing/`、APK、AAB、`outputs/` 和构建缓存不会提交到 GitHub。正式 APK 请放到 GitHub Release。

### 8.3 构建固件

Arduino CLI 示例：

```powershell
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware\Esp32_wifi_connect
```

如果 ArduinoJson 缺失，请先在 Arduino Library Manager 中安装 `ArduinoJson`。

## 9. 常见故障与解决方案

### 9.1 ESP32 BLE 扫描不到

- 确认 ESP32 已上电，并且串口输出进入 BLE 配网模式。
- 确认手机蓝牙、定位和附近设备权限已允许。
- 关闭手机蓝牙省电限制，必要时重启蓝牙后再试。
- 如果 ESP32 已保存 WiFi 并联网成功，它可能不会长期停留在配网模式，可长按 GPIO `11` 复位进入配网。

### 9.2 配网成功但 App 读不到传感器

- 确认手机和 ESP32 在同一个 `2.4G` 局域网。
- 如果手机自动切到其他 WiFi 或移动网络，App 会提示“连接的不是同个 WiFi”，并显示 ESP32 当前 WiFi 名称。
- 确认路由器没有开启 AP 隔离。
- 确认 ESP32 串口显示 HTTP 服务已启动。

### 9.3 MCP 工具没有数据返回

- 确认 App 主界面 MCP 服务开关处于开启状态。
- 确认 App 常驻通知没有被系统关闭。
- 确认最近 60 秒内有采集数据，缓存退出 App 后会清空。
- 如果系统杀后台服务，请给 App 允许后台运行、自启动和通知。

### 9.4 ADC 数值跳变严重

- 检查 FSR402 分压电阻是否接错。
- 检查 GND 是否可靠共地。
- 缩短传感器线材，避免贴近电机、电源线等干扰源。
- 可以在 AI 调用 `fsr_get_history` 时调高 `compressionTolerance`，减少稳定段被噪声拆碎。

### 9.5 找不到 WiFi 或密码错误

- `找不到该 WiFi，请确认名称或距离`：ESP32 没有扫描到这个 SSID，通常是路由器距离、隐藏网络、5G WiFi 或名称输入问题。
- `WiFi 密码错误`：SSID 能找到，但密码认证失败。
- ESP32 仅支持 `2.4G WiFi`，不支持 `5G WiFi`。

## 10. 安全说明

- MCP 服务没有身份验证，也没有 HTTPS 加密，只建议在可信家庭局域网内使用。
- 不要把手机 `9333` 端口映射到公网。
- BLE 配网会近距离明文传输 WiFi 名称和密码，不建议在公共场合配网。
- ESP32 HTTP API 没有访问鉴权，同一局域网内其他设备理论上也可以读取传感器数据。
- 二次分发或商用时必须保留本项目版权声明、协议条款和免责声明。

## 11. 已知局限和后续方向

已知局限：

- 当前主要面向 ESP32-S3，不保证兼容 ESP32-C3、ESP32-C6 或其他型号。
- 当前只适配 FSR402 薄膜压力传感器这类模拟传感器。
- App 不内置离线 AI，需要外部 MCP 客户端调用。
- MCP 服务是轻量本地 HTTP 实现，不适合公网或多客户端高并发。

后续方向：

- 增加 App 截图、硬件接线图和 AI 客户端配置截图。
- 增加自定义 ADC 滤波策略。
- 支持多 ESP32 设备同时接入。
- 支持更长时间的本地历史持久化。

## 12. 更新日志 Changelog

### V2.3.23

- 项目方向调整为 FSR402 传感器数据采集和 MCP 服务桥接。
- Android App 支持传感器命名、60 秒历史缓存、变化数据读取和 MCP tools。
- ESP32 固件支持 GPIO `1-10` 的 12 位 ADC 采集。
- 配网流程支持 BLE 回传 WiFi 错误，并在 App 中显示中文原因。
- 主界面支持 MCP 地址复制和 MCP 服务开关。
- App 支持深色模式，默认跟随系统。

## 13. 开源协议

本项目使用 BSD 2-Clause License。

该协议允许他人使用、修改、再发布源码或二进制版本，但再发布时必须保留开发者版权声明、协议条款和免责声明。

衍生修改版再分发时，建议在文档中说明主要改动，避免用户把修改版问题误认为原项目问题。
