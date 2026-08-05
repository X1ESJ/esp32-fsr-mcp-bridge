# ESP32 Firmware

当前版本：`V2.4.25`

固件路径：

```text
firmware/Esp32_wifi_connect/Esp32_wifi_connect.ino
```

## 硬件定义

| GPIO | 用途 |
| --- | --- |
| `1-10` | FSR402 模拟采集输入，12 位 ADC，范围 `0-4095` |
| `11` | 长按 5 秒清除 WiFi 和传感器配置 |
| `12` | 预留闲置，固件默认拉低，可作为传感器共地辅助 |
| 其他安全可用 GPIO | 固件默认拉低，可作为辅助负极使用 |

FSR402 推荐使用分压电路：

```text
3.3V -> FSR402 -> 分压点 -> 10k ohm 电阻 -> GND
                    |
                    +-> ESP32-S3 GPIO 1-10
```

禁止把 `5V` 直接接入 ADC 引脚。

## 编译环境

- Arduino IDE 或 Arduino CLI。
- ESP32 Arduino Core，建议 `2.0.14+` 或 `3.x`。
- ArduinoJson，需要通过 Arduino Library Manager 安装。
- 串口监视器波特率：`115200`。

Arduino CLI 示例：

```powershell
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware\Esp32_wifi_connect
```

## 配网流程

1. 首次启动或长按 GPIO `11` 清除配置后，ESP32 进入 BLE 配网模式。
2. App 通过 BLE 写入 WiFi 名称和密码。
3. ESP32 最多执行 `2` 轮 WiFi 连接流程。
4. 成功后回传 IP 和 SSID，并启动 HTTP 服务和 mDNS。
5. 失败后通过 BLE 回传失败原因，例如 `ssid_not_found` 或 `auth_failed`。

## HTTP API

读取接口：

- `GET /pins`
- `GET /snapshot`
- `GET /fsr/changes`
- `GET /status`

写入接口：

- `POST /pin/config`
- `POST /pin/delete`

`POST /pin/config` 使用 `application/x-www-form-urlencoded` 请求体：

```text
pin=1&direction=input&mode=analog&value=0&label=左耳
```

`POST /pin/delete` 请求体：

```text
pin=1
```

固件保留旧版 GET 写接口兼容，但新 App 使用 POST。

## 数据保存说明

- ESP32 Flash 只保存 WiFi 名称、WiFi 密码和传感器 GPIO 配置。
- 实时 FSR 历史、触摸事件、会话摘要和导出数据都保存在 Android App 私有数据区。
- App 默认每 `1s` 通过 HTTP 读取一次 ESP32 当前值；采样间隔可在 App 设置页调整。
