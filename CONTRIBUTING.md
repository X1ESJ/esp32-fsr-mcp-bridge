# Contributing

感谢你愿意改进 ESP32 FSR MCP Bridge。

## 提交 Issue

提交问题时，请尽量包含：

- Android 手机型号和系统版本。
- ESP32-S3 开发板型号。
- App 版本号。
- ESP32 串口日志，波特率为 `115200`。
- 问题复现步骤。

如果是 BLE 配网或 WiFi 连接问题，请同时说明手机当前连接的 WiFi、ESP32 串口中显示的 WiFi 错误原因，以及路由器是否为 2.4G。

## 提交 PR

提交代码前建议先完成：

- Android 修改后运行 `.\gradlew.bat assembleDebug`。
- ESP32 固件修改后使用 Arduino IDE 或 Arduino CLI 编译。
- 不提交 APK、AAB、keystore、`local-signing/`、`outputs/` 或构建缓存。
- UI 文案优先使用中文，WiFi、BLE、MCP、GPIO、ADC 等缩写保留英文。

## 代码风格

- Android 使用 Kotlin 和 Jetpack Compose。
- 异步逻辑优先使用 Kotlin Coroutines 和 StateFlow。
- ESP32 固件保持 Arduino `.ino` 单文件可编译，关键硬件逻辑添加中文注释。
- 新增对外协议字段时，请同步更新 README 和 App 内 MCP 工具详情。

## 二次分发

本项目使用 BSD 2-Clause License。你可以使用、修改和再分发，但必须保留原作者版权声明、协议条款和免责声明。
