# ESP32 FSR MCP Bridge

Android + ESP32-S3 project for reading FSR402 pressure sensor data from ADC-capable GPIO pins over WiFi, caching the latest 60 seconds on the phone, and exposing that data to third-party AI chat apps through a local MCP server.

当前版本：`V2.3.23`

作者：琳云 XESJ  
邮箱：xianeshijie@outlook.com

## 1. 项目方向

本项目通过 WiFi 接收 ESP32-S3 支持 ADC 的 GPIO 端口数据，并通过手机本地 MCP 服务获取最近 60 秒内的引脚数据。

整体链路是：

```text
FSR402 薄膜压力传感器
    -> ESP32-S3 ADC GPIO 1-10
    -> ESP32 HTTP API / mDNS / BLE 错误回传
    -> Android App 本地缓存最近 60 秒
    -> 手机本地 MCP Server
    -> 第三方 AI 聊天应用调用 MCP tools
```

这个项目的重点不是普通“遥控开关”，而是把多个传感器的实时模拟值变成 AI 应用可查询、可分析、可持续观察的数据源。

## 2. 快速使用教程

1. 使用 Arduino IDE 或 Arduino CLI 给 ESP32-S3 烧录 `firmware/Esp32_wifi_connect/Esp32_wifi_connect.ino`。
2. 给 FSR402 传感器接线，模拟输入接到 GPIO `1-10` 中任意端口。
3. 打开 Android App，授予蓝牙、定位、通知和 WiFi 相关权限。
4. 点击主界面右上角“配对”，选择扫描到的 ESP32。
5. 在配网页面选择 `2.4G WiFi`，输入密码并确认。
6. 配网完成后，进入传感器配置页，为每个 GPIO 添加名称，例如“左耳”“身体”“右前肢”。
7. 回到主界面确认 MCP 服务已开启，复制 MCP 地址。
8. 在支持 MCP 的第三方 AI 聊天应用中添加该地址，例如 `http://手机局域网IP:9333/mcp`。
9. 让 AI 调用 `fsr_get_snapshot`、`fsr_get_changes` 或 `fsr_get_history` 获取传感器数据。

如果 ESP32 已经连上 WiFi，但手机无法通过 WiFi 访问设备，App 会提示 ESP32 连接的 WiFi 名称。此时通常是手机自动切到了其他 WiFi 或移动网络，需要把手机切回同一局域网。

## 3. 核心能力

### 3.1 ESP32 数据采集

- GPIO `1-10` 可作为 FSR402 模拟输入。
- ADC 分辨率为 12 位，原始范围 `0-4095`。
- ESP32 每次 HTTP 查询时读取当前配置的传感器值。
- `/fsr/changes` 只返回变化数据，减少 App 和 ESP32 之间的重复传输。

### 3.2 配网和设备发现

- App 通过 BLE 给 ESP32 写入 WiFi 名称和密码。
- ESP32 成功入网后通过 BLE 回传 IP 和已连接的 WiFi 名称。
- App 会立刻尝试通过 WiFi 访问 ESP32。
- 如果 ESP32 已连接 WiFi 但手机无法访问，App 会提示手机和 ESP32 可能不在同一 WiFi，并显示 ESP32 连接的 WiFi 名称。
- 如果设备 IP 改变，App 会通过 mDNS 查询 `esp32.local`。
- WiFi 密码错误、找不到 WiFi、连接超时等错误会用中文显示在 App 中。

### 3.3 手机本地缓存

- App 后台每 `0.5s` 采集一次 ESP32 数据。
- 最近 60 秒的数据保存在手机内存缓存中。
- 缓存退出 App 后清空，不写入长期历史数据库。
- 稳定数据会按容差压缩成片段，减少 MCP 返回体积。

### 3.4 MCP 服务

- App 在手机本地启动 HTTP MCP Server。
- 默认端口：`9333`。
- 主界面显示 MCP 地址，并支持一键复制。
- MCP 服务可在 App 内单独开启或关闭。
- 关闭 MCP 不会停止 ESP32 采集，重新开启后可继续读取当前缓存。

### 3.5 Android 后台运行

- App 使用前台 Service 和常驻通知保持后台采集。
- 建议在手机系统设置中允许后台运行、自启动和通知。
- 支持深色模式，默认跟随系统。

## 4. 硬件约定

- 主控：ESP32-S3。
- 传感器：FSR402 薄膜压力传感器。
- ADC 输入：GPIO `1-10`。
- 重置按钮：GPIO `11`，长按 5 秒清除 WiFi 和配置。
- ESP32 仅支持 2.4G WiFi，不支持 5G WiFi。
- 除 GPIO `1-12` 外，固件会把可用 GPIO 设置为低电平，可作为简易辅助负极使用。

## 5. 仓库结构

```text
.
├── app/                         # Android App 源码
├── firmware/Esp32_wifi_connect/ # ESP32 Arduino 固件
├── gradle/                      # Gradle Wrapper
├── LICENSE                      # BSD 2-Clause License
├── README.md                    # 项目说明
└── release.properties.example   # Release 签名配置示例
```

## 6. ESP32 固件

固件文件：

```text
firmware/Esp32_wifi_connect/Esp32_wifi_connect.ino
```

BLE 配网协议：

- Service UUID：`0000FFFF-0000-1000-8000-00805F9B34FB`
- App 写入 Characteristic：`0000FF01-0000-1000-8000-00805F9B34FB`
- ESP32 回传 Characteristic：`0000FF02-0000-1000-8000-00805F9B34FB`

BLE 数据格式：

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

App 会把 `ssid_not_found` 翻译成“找不到该 WiFi，请确认名称或距离”。

主要 HTTP API：

- `GET /pins`：返回可用 GPIO、当前 IP、当前 WiFi 名称和已配置传感器。
- `GET /snapshot`：返回完整传感器快照。
- `GET /fsr/changes`：返回最近变化的传感器数据。
- `GET /pin/config`：保存某个 GPIO 的 FSR 输入配置。
- `GET /pin/delete`：删除某个 GPIO 的配置。
- `GET /status`：兼容旧版状态接口。

## 7. Android App

技术栈：

- Kotlin
- Jetpack Compose
- Android BLE Scanner / BluetoothGatt
- Retrofit + OkHttp
- Kotlin Coroutines
- StateFlow
- DataStore
- Android NsdManager
- Foreground Service

主要页面：

- 主界面：设备状态、ESP32 报错、MCP 状态、本机 MCP 地址、传感器概览。
- 配网页面：BLE 扫描、WiFi 选择、密码输入、配网进度、完成提示。
- 传感器配置页：添加 GPIO `1-10` 的模拟输入，并为每个传感器命名。
- MCP 工具详情页：展示每个 MCP tool 的名称、功能、传入值和返回值。

## 8. MCP Tools

默认 MCP 地址格式：

```text
http://手机局域网IP:9333/mcp
```

当前 tools：

- `fsr_list_sensors`：列出当前配置的全部传感器。
- `fsr_get_snapshot`：获取全部传感器的实时快照。
- `fsr_get_sensor`：按传感器名称、GPIO 或 key 获取单个传感器。
- `fsr_get_changes`：获取上次 cursor 之后发生变化的数据。
- `fsr_get_history`：获取最近 60 秒历史数据，包含抽样点和压缩稳定段。

推荐第三方 AI 应用优先用 App 中设置的传感器名称作为参数，例如“左耳”“身体”“右前肢”。

## 9. 构建 App

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

`local-signing/`、APK 输出和构建缓存不会提交到 GitHub。

## 10. 构建固件

使用 Arduino IDE 或 Arduino CLI，开发板选择 ESP32-S3。

Arduino CLI 示例：

```powershell
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware\Esp32_wifi_connect
```

首次启动如果没有保存 WiFi，ESP32 会进入 BLE 配网模式。

## 11. 版本规则

版本格式：`V主版本.UI版本.功能版本`

- 功能方向大改变：改第一位，例如 `V3.3.23`。
- UI 大改动：改第二位，例如 `V2.4.23`。
- 小功能添加、删除或修正：最后一位加一，例如 `V2.3.24`。

## 12. 开源协议

本项目使用 BSD 2-Clause License。

该协议允许他人使用、修改、再发布源码或二进制版本，但再发布时必须保留开发者版权声明、协议条款和免责声明。
