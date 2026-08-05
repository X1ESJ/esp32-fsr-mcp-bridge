# ESP32 FSR MCP Bridge

Licensed under BSD 2-Clause License.

当前版本：`V2.5.0`

本项目通过 WiFi 接收 ESP32-S3 支持 ADC 的 GPIO 端口数据，在 Android App 私有数据区持续保存 FSR402 压力传感器记录，并通过手机本地 MCP 服务让第三方 AI 聊天应用读取实时值、短期历史、触摸事件和会话摘要。

App 默认保留 `60 秒` 短期历史用于实时 MCP 查询，同时把所有采样追加写入手机本地 SQLite 数据库。可选 Supabase 云同步由 App 直接通过 PostgREST 上传分钟聚合和会话摘要，MCP 不参与上传链路。

灵感来源：群友想要制作“共感娃娃”，本人提供 WiFi 和 BLE 连接思路，最后借助 AI 写出本项目。

## 目录 Table of Contents

- [1. 快速使用教程](#1-快速使用教程)
- [2. 项目链路](#2-项目链路)
- [3. 硬件接线指南](#3-硬件接线指南)
- [4. ESP32 固件](#4-esp32-固件)
- [5. Android App](#5-android-app)
- [6. MCP 服务和工具](#6-mcp-服务和工具)
- [7. HTTP API](#7-http-api)
- [8. 构建和发布](#8-构建和发布)
- [9. 常见故障与解决方案](#9-常见故障与解决方案)
- [10. 安全说明](#10-安全说明)
- [11. 演示场景和截图](#11-演示场景和截图)
- [12. 已知局限和后续方向](#12-已知局限和后续方向)
- [13. 更新日志 Changelog](#13-更新日志-changelog)
- [14. 开源协议](#14-开源协议)

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
3. 让 AI 调用 `fsr_get_snapshot`、`fsr_get_changes` 或 `fsr_get_history` 读取实时和短期数据。
4. 如果要问“昨晚有没有被抱着睡”，优先调用 `fsr_list_sessions`、`fsr_get_session_summary` 或 `fsr_get_events`，不要直接拉整晚原始点。

### 1.5 设置和导出

1. 点击主界面右上角设置按钮。
2. 在“采样与短期历史”里设置短期历史保存时间，默认 `60 秒`。
3. 在“保存频率”里设置采样保存间隔，默认 `1000ms`。
4. 在“本地数据库”里点击“导出 JSON”，导出不会清空旧记录，也不会中断新记录写入。

### 1.6 Supabase 云同步

1. 在 Supabase 创建 `fsr_sessions` 和 `fsr_minute_data` 两张表，参考本文后面的建表 SQL。
2. 在 App 设置页填写 Supabase 项目地址和 `anon key`。
3. 打开“Supabase 云同步”开关。
4. App 每分钟把分钟聚合和会话摘要直接上传到 Supabase。
5. 第三方 AI 如果要查云端长期摘要，可以单独添加 Supabase 官方 MCP，让它查询 `fsr_sessions` 表。
6. 如果你还想额外接手机侧别的设备数据表，可参考仓库里的 [docs/device_data_supabase_example.md](docs/device_data_supabase_example.md)；这个表是扩展示例，不是当前 App 默认上传目标。

如果 ESP32 已经连上 WiFi，但手机无法通过 WiFi 访问设备，App 会提示 ESP32 当前连接的 WiFi 名称。此时通常是手机自动切到了其他 WiFi 或移动网络，需要把手机切回同一局域网。

## 2. 项目链路

```text
FSR402 薄膜压力传感器
    -> ESP32-S3 ADC GPIO 1-10
    -> ESP32 HTTP API / mDNS / BLE 错误回传
    -> Android App 私有 SQLite 数据库持续记录
    -> App 本地规则生成触摸事件和会话摘要
    -> 可选 Supabase PostgREST 上传分钟聚合
    -> 手机本地 MCP Server
    -> 第三方 AI 聊天应用调用 MCP 工具查询摘要或实时值
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

复位按钮接线：

```text
GPIO11 -> 按键 -> GND
```

### 3.3 供电建议

- ESP32-S3 推荐使用稳定的 `5V/1A` 供电。
- 多个 FSR402 同时接入时，建议使用可靠的 GND 汇流连接，避免线材过细导致读数跳变。
- 传感器线较长时，读数可能被干扰，可以缩短线材、整理地线，或在软件侧调高变化阈值。
- 固件把部分 GPIO 拉低只是辅助共地方案，不能替代稳定可靠的主 GND 接线。

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
- SQLite
- Android NsdManager
- Supabase PostgREST，可选
- Foreground Service

系统建议：

- 工程 `minSdk` 为 `26`，建议 Android 13 及以上使用。
- Android 12 及以上需要蓝牙扫描和蓝牙连接权限。
- Android 13 及以上需要通知权限，前台服务常驻通知依赖该权限。
- Android 13 及以上还需要附近 WiFi 设备权限，工程已声明 `NEARBY_WIFI_DEVICES`。
- Android 14 及以上会更严格限制前台服务类型，工程已声明 `connectedDevice` 和 `dataSync`。
- mDNS 自动发现依赖局域网和多播能力，App 已声明 `CHANGE_WIFI_MULTICAST_STATE`。

后台运行：

- App 使用前台 Service 和常驻通知保持后台采集。
- 华为、OPPO、vivo、小米等系统可能会限制后台服务，建议手动允许后台运行、自启动和通知。
- 如果 MCP 工具长时间没有数据，先打开 App 主界面确认前台服务和 ESP32 在线状态。

常见品牌设置关键词：

- 华为 / 鸿蒙：电池优化、应用启动管理、允许自启动、允许后台活动、允许通知。
- OPPO / ColorOS：电池、后台耗电管理、允许后台运行、允许自启动、锁定最近任务。
- vivo / OriginOS：电池、后台高耗电、允许后台运行、允许自启动、允许通知。
- 小米 / HyperOS / MIUI：省电策略改为无限制、允许自启动、锁定后台、允许通知。

性能参数：

- ESP32 传感器采集由 App 轮询触发，默认每 `1000ms` 读取一次。
- App 设置页可选择 `250ms`、`500ms`、`1000ms`、`2000ms` 或自定义保存频率。
- 短期历史默认保留 `60s`，可选择 `15s`、`30s`、`45s`、`60s` 或自定义。
- 所有采样会追加写入手机本地 SQLite 数据库，不覆盖、不因导出而中断。
- 长时间 AI 查询优先读取事件、会话和分钟摘要，避免把整晚原始点直接塞进上下文。
- MCP 默认只建议 1 个 AI 客户端连接；多个客户端同时高频调用会排队，手机性能较弱时可能变慢。

自动发现和降级逻辑：

- App 会先尝试上次保存的 ESP32 IP。
- 如果 IP 失效，会用 mDNS 解析 `esp32.local` 并更新保存的 IP。
- 如果 WiFi 访问失败，后台会同步扫描 ESP32 BLE 状态消息，用于显示 WiFi 密码错误、找不到 WiFi 等原因。
- 如果 mDNS 和 BLE 状态扫描都无法恢复连接，主界面会保留重试入口，必要时重新配对。

主要页面：

- 主界面：设备状态、ESP32 报错、MCP 状态、本机 MCP 地址、传感器概览。
- 配网页面：BLE 扫描、WiFi 选择、密码输入、配网进度、完成提示。
- 传感器配置页：添加 GPIO `1-10` 的模拟输入，并为每个传感器命名。
- 设置页：调整短期历史、保存频率、触发阈值、导出本地 JSON、配置 Supabase 云同步。
- MCP 工具详情页：展示每个 MCP 工具的名称、功能、传入值和返回值。

### 5.1 Supabase 建表 SQL

如果只在本地使用，可以不配置 Supabase。需要云端长期摘要时，在 Supabase SQL Editor 执行下面的建表语句，然后把项目地址和 `anon key` 填进 App 设置页。

```sql
create table if not exists public.fsr_sessions (
  id text primary key,
  start_ms bigint not null,
  end_ms bigint not null,
  duration_ms bigint not null,
  avg_pressure real not null,
  max_pressure integer not null,
  hug_count integer not null,
  poke_count integer not null,
  pinch_count integer not null,
  stroke_count integer not null,
  press_count integer not null,
  summary text not null,
  updated_at_ms bigint not null,
  created_at timestamptz not null default now()
);

create table if not exists public.fsr_minute_data (
  id text primary key,
  session_id text not null,
  minute_start_ms bigint not null,
  device_mac text,
  device_name text,
  samples integer not null,
  sensor_values jsonb not null,
  summary text not null,
  created_at timestamptz not null default now()
);
```

个人实验最简权限：

```sql
alter table public.fsr_sessions enable row level security;
alter table public.fsr_minute_data enable row level security;

create policy "fsr_sessions_anon_read" on public.fsr_sessions
for select to anon using (true);

create policy "fsr_sessions_anon_insert" on public.fsr_sessions
for insert to anon with check (true);

create policy "fsr_sessions_anon_update" on public.fsr_sessions
for update to anon using (true) with check (true);

create policy "fsr_minute_anon_read" on public.fsr_minute_data
for select to anon using (true);

create policy "fsr_minute_anon_insert" on public.fsr_minute_data
for insert to anon with check (true);

create policy "fsr_minute_anon_update" on public.fsr_minute_data
for update to anon using (true) with check (true);
```

说明：上面的策略适合个人局域网/实验环境。公开项目请改成更严格的鉴权方式，不要把可写 anon key 暴露给不可信用户。

可选扩展：

- 如果你除了 FSR 会话摘要之外，还想把别的手机侧设备数据同步到 Supabase，可以参考 [docs/device_data_supabase_example.md](docs/device_data_supabase_example.md)。
- 该示例只提供表结构和权限思路，当前 App 默认仍然只上传 `fsr_sessions` 与 `fsr_minute_data`。

## 6. MCP 服务和工具

默认 MCP 地址：

```text
http://手机局域网IP:9333/mcp
```

安全边界：

- MCP 服务只设计给同一局域网内使用。
- 默认端口：`9333`。
- 当前没有身份验证和加密，不要映射到公网。
- 建议同一时间只连接一个 AI 客户端，多客户端同时高频调用会排队。

HTTP MCP 客户端配置示例，字段名以客户端实际版本为准。

Claude Desktop 示例，如果客户端版本支持 HTTP MCP：

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

Cursor 示例，可放到项目级 `.cursor/mcp.json` 或客户端设置里的 MCP 配置中：

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

如果某个 AI 客户端只支持 `stdio` MCP，不支持 HTTP MCP，需要额外使用 HTTP 到 stdio 的 MCP 代理；代理配置不属于本项目内置功能。

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

功能：紧凑获取全部传感器的实时快照。传感器身份映射请先调用 `fsr_list_sensors`，高频读取时不重复返回 GPIO、key、source。

传入值：

```json
{}
```

返回示例：

```json
{
  "t": 1785930000000,
  "online": true,
  "max": 4095,
  "cols": ["s", "v", "d", "ageMs"],
  "data": [["左耳", 2300, 50, 120]]
}
```

### 6.3 `fsr_get_sensor`

功能：按用户命名、GPIO 或 key 紧凑获取单个传感器。

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

命中时返回：

```json
{"s":"左耳","v":2300,"d":50,"ageMs":120}
```

### 6.4 `fsr_get_changes`

功能：紧凑获取上一次调用之后发生变化的数据，只返回变化项。

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
  "next": 15,
  "minD": 8,
  "t0": 1785924541590,
  "cols": ["s", "dt", "v", "d"],
  "data": [["左耳", 0, 2300, 50]]
}
```

说明：`s` 是传感器名称，`dt` 是相对 `t0` 的毫秒偏移，`v` 是当前 ADC 值，`d` 是带正负号差值。这里不再重复返回 `pin`、`key`、`source`、`previousValue` 和 `absoluteDelta`。

### 6.5 `fsr_get_history`

功能：紧凑获取 App 私有数据区的短期历史缓存。默认返回压缩段，需要原始点时传 `mode=raw` 或 `includeRaw=true`。

传入值：

```json
{
  "names": ["左耳"],
  "lastMs": 60000,
  "intervalMs": 1000,
  "compressionTolerance": 15,
  "mode": "segments"
}
```

默认返回示例：

```json
{
  "t0": 1785924689000,
  "to": 1785924809000,
  "max": 4095,
  "mode": "segments",
  "cols": ["from", "to", "v"],
  "series": [
    {"s": "左耳", "data": [[0, 2500, 941], [2500, 3000, 1020]]}
  ]
}
```

原始点返回示例：

```json
{
  "t0": 1785924689000,
  "to": 1785924809000,
  "max": 4095,
  "mode": "raw",
  "cols": ["t", "v"],
  "series": [
    {"s": "左耳", "data": [[0, 941], [1000, 950]]}
  ]
}
```

规则：

- 默认短期历史为 `60s`，默认保存频率为 `1000ms`。
- 短期历史和保存频率都可以在 App 设置页修改。
- 默认压缩容差为 `±15` 个 ADC 值。
- `segments` 模式会把连续相邻、且相对区间起点和上一点都没有超过容差的采样合并为 `[from,to,v]` 区间记录。
- 当前压缩逻辑没有硬性要求连续 `20` 帧才合并，这样可以保留短时间触摸动作的细节。
- 如果需要原始点，请传 `mode=raw`；如果只看趋势，默认 `segments` 最省 token。
- ESP32 Flash 只保存 WiFi 和传感器配置，不保存实时传感器历史。

AI Prompt 示例：

```text
请调用 fsr_get_history，读取“左耳”和“身体”最近 60 秒数据，
判断是否出现持续按压、短促点击或逐渐加重的压力变化。
```

### 6.6 `fsr_list_sessions`

功能：读取手机本地数据库里的最近触摸会话摘要，适合白天询问昨晚发生了什么。

传入值：

```json
{
  "limit": 12,
  "sinceMs": 1785900000000
}
```

返回示例：

```json
{
  "cols": ["id", "from", "to", "durS", "max", "summary"],
  "data": [
    ["s-1785924000000-a1b2c3d4", 1785924000000, 1785952800000, 28800, 2310, "持续 28800.00 秒，当前接触：身体；峰值 2310；抱住 1 次，捏 0 次，抚摸 0 次，戳 0 次，按 2 次"]
  ]
}
```

### 6.7 `fsr_get_session_summary`

功能：读取单个会话的摘要和少量事件。不传 `id` 时默认读取最近会话。

传入值：

```json
{
  "id": "s-1785924000000-a1b2c3d4",
  "limit": 40
}
```

返回示例：

```json
{
  "id": "s-1785924000000-a1b2c3d4",
  "from": 1785924000000,
  "to": 1785952800000,
  "durS": 28800,
  "avg": 860,
  "max": 2310,
  "counts": {"抱住不放": 1, "捏": 0, "抚摸": 0, "戳": 0, "按": 2},
  "summary": "持续 28800.00 秒，当前接触：身体；峰值 2310；抱住 1 次，捏 0 次，抚摸 0 次，戳 0 次，按 2 次",
  "eventCols": ["dt", "type", "s", "durMs", "peak"],
  "events": [[0, "抱住不放", "身体", 5740, 2310]]
}
```

### 6.8 `fsr_get_events`

功能：按时间窗口读取 App 本地已经分类好的触摸事件。

事件规则：

- 某个通道连续 `20` 次安静后才参与事件判断，避免接线坏的通道乱报。
- 默认触发阈值为 `300`，可在 App 设置页修改。
- 短于 `0.4s` 判为“戳”。
- 超过 `2.5s` 判为“抱住不放”。
- 两个或更多传感器同时触发判为“捏”。
- 短时间内连续扫过 3 个或更多传感器判为“抚摸”。
- 其他情况判为“按”。

传入值：

```json
{
  "lastMs": 28800000,
  "type": "抱住不放",
  "limit": 80
}
```

返回示例：

```json
{
  "t0": 1785924000000,
  "cols": ["dt", "type", "s", "durMs", "peak"],
  "data": [[0, "抱住不放", "身体", 5740, 2310]]
}
```

### 6.9 `fsr_get_window`

功能：读取长期数据库里的分钟级摘要窗口，适合低 token 查看整晚趋势。

传入值：

```json
{
  "lastMs": 28800000,
  "limit": 480
}
```

返回示例：

```json
{
  "t0": 1785924000000,
  "to": 1785952800000,
  "mode": "minute",
  "cols": ["dt", "summary", "n"],
  "data": [[0, "本分钟 60 次采样，最高 身体=2310", 60]]
}
```

### 6.10 建议 AI 查询方式

低 token 默认顺序：

1. 先调用 `fsr_list_sensors` 确认名称。
2. 问“现在怎样”时调用 `fsr_get_snapshot` 或 `fsr_get_changes`。
3. 问“最近一分钟”时调用 `fsr_get_history`。
4. 问“昨晚/长时间”时调用 `fsr_list_sessions`。
5. 需要细节时再调用 `fsr_get_session_summary` 或 `fsr_get_events`。
6. 需要趋势时调用 `fsr_get_window`，不要直接读取整晚原始采样。

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
- 确认路由器或手机热点没有拦截局域网内的 `80` 端口。
- MCP 端口 `9333` 是手机提供给 AI 客户端访问的端口，不是 ESP32 端口；不要把两个端口混在一起排查。

### 9.3 MCP 工具没有数据返回

- 确认 App 主界面 MCP 服务开关处于开启状态。
- 确认 App 常驻通知没有被系统关闭。
- 确认设置页里的短期历史窗口内有采集数据；长期数据请改用 `fsr_list_sessions` 或 `fsr_get_events` 查询。
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

## 11. 演示场景和截图

共感娃娃场景示例：

1. 在左耳、右耳、身体、前肢等位置放置 FSR402。
2. 在 App 中把 GPIO 命名为“左耳”“右耳”“身体”等自然语言名称。
3. AI 客户端调用 `fsr_get_changes` 观察最新触摸变化。
4. AI 客户端调用 `fsr_get_history` 分析最近短期压力趋势。
5. AI 客户端调用 `fsr_list_sessions` 或 `fsr_get_events` 分析整晚事件。
6. 聊天应用根据事件摘要判断“短按”“持续按压”“轻抚”“突然用力”等动作。

多传感器 AI Prompt 示例：

```text
请读取“左耳”“右耳”“身体”最近 60 秒的 FSR 历史，
判断有没有明显的持续按压、快速点击或左右两侧交替触摸。
回答时给出你依据的传感器名称、时间段和压力变化。
```

截图占位：

- App 主界面截图：后续补充。
- 传感器配置页截图：后续补充。
- 硬件接线图：后续补充。
- AI 客户端 MCP 配置截图：后续补充。

## 12. 已知局限和后续方向

已知局限：

- 当前主要面向 ESP32-S3，不保证兼容 ESP32-C3、ESP32-C6 或其他型号。
- 当前只适配 FSR402 薄膜压力传感器这类模拟传感器。
- App 不内置离线 AI，需要外部 MCP 客户端调用。
- MCP 服务是轻量本地 HTTP 实现，不适合公网或多客户端高并发。
- `0.01s` 这种显示精度来自毫秒时间戳格式化；如果保存频率设为 `1000ms`，事件边沿精度不能当成真实百分之一秒级测量。

后续方向：

- 增加 App 截图、硬件接线图和 AI 客户端配置截图。
- 增加蓝牙直连 MCP 或离线降级方案。
- 增加自定义 ADC 滤波策略。
- 支持多 ESP32 设备同时接入。

## 13. 更新日志 Changelog

### V2.5.0

- 主界面、设置页和配网页补上弹性按压反馈，主要按钮和可点卡片的交互手感更统一。
- 传感器值、变化量和统计数字改为逐位滚动的锁拨盘动画，只滚动发生变化的数字位。
- 配网页加入步骤过渡动画，并在分段进度条下展示每一步的核心说明。
- 保存频率预设改为 `250ms`、`500ms`、`1000ms`、`2000ms`。
- 新增 `docs/device_data_supabase_example.md`，整理可选的 Supabase `device_data` 扩展示例。
- 修正发布说明文案与版本同步，避免 GitHub Release 出现乱码和版本对不上的情况。

### V2.4.25

- 新增设置页，可调整短期历史窗口、保存频率和触发阈值。
- 新增手机本地 SQLite 数据库，采样数据持续追加保存，并支持 JSON 导出。
- 新增触摸事件和会话摘要：戳、按、抱住不放、捏、抚摸。
- 新增长期 MCP 查询工具：`fsr_list_sessions`、`fsr_get_session_summary`、`fsr_get_events`、`fsr_get_window`。
- 新增 Supabase 云同步配置，App 每分钟通过 PostgREST 直接上传分钟聚合和会话摘要。

### V2.3.25

- MCP 高频数据工具改为紧凑返回格式，减少 token 消耗。
- `fsr_get_changes` 删除重复字段，只返回传感器名、相对时间、当前值和带正负号差值。
- `fsr_get_history` 默认返回压缩段 `[from,to,v]`，需要原始点时可传 `mode=raw`。
- 传感器 GPIO、key、source 等身份信息集中在 `fsr_list_sensors` 返回。

### V2.3.24

- App 历史缓存从 60 秒改为 2 分钟。
- 传感器历史写入 Android App 私有数据区滚动缓存，避免进程短暂重建导致历史断层。
- 明确 ESP32 Flash 只保存 WiFi 和传感器配置，不保存实时传感器历史。

### V2.3.23

- 项目方向调整为 FSR402 传感器数据采集和 MCP 服务桥接。
- Android App 支持传感器命名、60 秒历史缓存、变化数据读取和 MCP tools。
- ESP32 固件支持 GPIO `1-10` 的 12 位 ADC 采集。
- 配网流程支持 BLE 回传 WiFi 错误，并在 App 中显示中文原因。
- 主界面支持 MCP 地址复制和 MCP 服务开关。
- App 支持深色模式，默认跟随系统。

## 14. 开源协议

本项目使用 BSD 2-Clause License。

该协议允许他人使用、修改、再发布源码或二进制版本，但再发布时必须保留开发者版权声明、协议条款和免责声明。

衍生修改版再分发时，建议在文档中说明主要改动，避免用户把修改版问题误认为原项目问题。

## 15.费用

本项目由 姚 大人赞助的资金推动创建，不会向任何人收取除此之外的任何费用 ^_^
<img width="408" height="390" alt="image" src="https://github.com/user-attachments/assets/8507b82e-db38-46d1-95bd-29763fc75de8" />


作者：琳云 XESJ

邮箱：xianeshijie@outlook.com
