# Windows 项目本地开发环境

工具链安装在 `tmp/toolchain/`，该目录已被仓库原有 `/tmp/` 规则忽略；没有修改系统 PATH 或系统 Java。
`local.properties` 指向本项目的 `tmp/toolchain/android-sdk`，也不入 Git。

## 使用

每次新开 PowerShell，在仓库根目录执行：

```powershell
. .\tools\Use-DevEnvironment.ps1
java -version
.\gradlew.bat --version
.\gradlew.bat :core:test :reader:test :app:testDebugUnitTest :source-auth:testDebugUnitTest
.\gradlew.bat assembleDebug
```

脚本只启用已安装的工具，不自动下载。移动仓库后需调整 `local.properties` 的 `sdk.dir`；脚本内其余路径由仓库位置推导。

## 本次部署

| 工具 | 版本 / 路径 | 官方来源与验证 |
|---|---|---|
| JDK | Azul Zulu OpenJDK 21.0.13.0.3，`tmp/toolchain/jdk/` | [Azul 官方包元数据](https://api.azul.com/metadata/v1/zulu/packages/4ee30e3a-b26a-40d3-9d04-2182eae4075e)，SHA-256 `7428890c66267aac516514085745dbedc6b8e7703eb0f7d73fb65cf9cd2cf306` |
| Android 命令行工具 | Windows 包 15859902，`android-sdk/cmdline-tools/latest/` | [Google 官方下载页](https://developer.android.com/studio#command-tools)，SHA-256 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` |
| Android SDK | `platforms;android-37.1`、`build-tools;37.0.0`、`build-tools;36.0.0`、`platform-tools` 37.0.1 | 官方 sdkmanager 安装，所需许可已在本地接受；36.0.0 由首次 Gradle 构建自动补装 |
| Gradle | 仓库 Wrapper 指定的 9.9.1 | [Gradle 官方分发目录](https://downloads.gradle.org/distributions/)，[官方 SHA-256](https://services.gradle.org/distributions/gradle-9.9.1-bin.zip.sha256)：`d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228` |

下载及校验元数据保留在 `tmp/toolchain/downloads/`。微软 JDK 下载停滞后改用 Azul 官方包；只解压了已通过 SHA-256 的 Azul JDK。

Gradle 下载包通过与 Wrapper 相同的 URL 哈希目录预填到本地 Wrapper 缓存，未改仓库 `distributionUrl` 或依赖版本。
本机网络需要已有代理 `127.0.0.1:7920`；Gradle 代理仅写入忽略目录中的 `tmp/toolchain/gradle-cache/gradle.properties`，不进版本库。如果代理地址变化，请修改该本地文件。

## 缓存与验证证据

- `GRADLE_USER_HOME`：`tmp/toolchain/gradle-cache/`
- `ANDROID_USER_HOME`：`tmp/toolchain/android-user/`
- `ANDROID_AVD_HOME`：`tmp/toolchain/avd/`；`ANDROID_EMULATOR_HOME` 指向项目本地 android-user
- SDK：`tmp/toolchain/android-sdk/`
- 模块任务日志：`tmp/toolchain/gradle-tasks.log`

阶段 A 已通过 1029 项 JVM 测试；阶段 B 最终通过 1039 项 JVM 测试、debug APK 构建与 Android 36 上 2 项仪器测试。
已增加官方 emulator 与 Android 35 / 36 Google APIs x86_64 镜像，WHPX 可用；测试结束关闭后台模拟器，AVD 保留在项目本地目录。
完整结果与限制见 [阶段 B 记录](开发记录/2026-11-16-阶段B.md)；没有进行真实网站登录验证。

重用设备测试环境（无窗口启动，便于命令行测试）：

```powershell
. .\tools\Use-DevEnvironment.ps1
Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList '-avd inkwell_auth36_test -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -port 5558' -WindowStyle Hidden
# adb devices 显示 device 且 sys.boot_completed=1 后运行测试
$env:ANDROID_SERIAL = 'emulator-5558'
adb -s emulator-5558 shell getprop sys.boot_completed
.\gradlew.bat :source-auth:connectedDebugAndroidTest
adb -s emulator-5558 emu kill
```

## 2026-11-17 当前电脑重新部署与真机工具

本次检出不含此前忽略的 `tmp/toolchain/`，已重新下载项目本地工具链；不是复用前文的模拟器或设备测试结果。没有修改系统 PATH、系统 Java、应用源码或手机数据。

- Azul JDK 21.0.13.0.3 与 Android 命令行工具 15859902：下载后分别通过上表 SHA-256 校验。
- Android SDK：已通过 `sdkmanager --list_installed` 核对 `platforms;android-37.1`、`build-tools;37.0.0`、`build-tools;36.0.0` 和 `platform-tools` 37.0.1。
- Gradle 9.9.1：官方分发包 SHA-256 与上表一致；`./gradlew.bat --version` 成功，使用本地 Azul JDK 21，证据为 `tmp/toolchain/gradle-version.log`。本轮只检查工具启动，尚未运行项目编译或单元测试，Maven 依赖会在后续首次构建时下载。
- ADB 37.0.1：`adb version` 成功；初次安装时启动了本地 ADB 服务，设备列表为空。
- scrcpy 5.0.1：从 Genymobile 官方 GitHub Release 下载 Windows x64 包，SHA-256 与 Release 资产 digest 一致，为 `b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5`；`scrcpy --version` 成功。目录：`tmp/toolchain/scrcpy/scrcpy-win64-v5.0.1/`。
- Mobile MCP 1.0.17：已本地安装到 `tmp/toolchain/mobile-mcp/` 并注册为用户级 Codex MCP 服务 `mobile-mcp`。使用现有 Node 24.22.3、绝对脚本路径和本地 SDK，stdio 通信，设置 `MOBILEMCP_DISABLE_TELEMETRY=1`；不启用云端设备或 HTTP 监听。

Mobile MCP 的 `initialize` 和 `tools/list` 成功，证据为 `tmp/toolchain/mobile-mcp-handshake.json`；仅验证服务协议，没有调用任何设备工具。安装日志为 `tmp/toolchain/mobile-mcp-install.log`。新增 MCP 是否已被当前聊天加载，需以 Codex 实际工具列表为准；必要时刷新 MCP 或重启应用。

下载哈希清单保存在 `tmp/toolchain/environment-install-evidence.json`；SDK 安装清单保存在 `tmp/toolchain/sdk-installed.log`。APK 签名与资源检查工具 `apksigner version`、`aapt2 version` 均可正常运行。`apkanalyzer --version` 不支持该参数，本轮仅观察到帮助提示，不将其计为 APK 分析通过。

用户要求环境安装完成后由其决定是否开始测试。当前未安装 APK、未操作手机、未输入账号、未执行真实站点登录；登录保存后的校验错误仍待实机复现。开始测试前先核对手机 APK 的版本、签名和调试属性，不能以卸载或清空应用数据解决签名冲突。

用户决定开始测试后，连接手机并允许 USB 调试；在项目环境中可使用：

```powershell
. .\tools\Use-DevEnvironment.ps1
adb devices -l
# 仅在用户开始测试后启动投屏，不录制账号输入。
& .\tmp\toolchain\scrcpy\scrcpy-win64-v5.0.1\scrcpy.exe --serial <设备序列号>
```

上述目录均在忽略的 `tmp/` 下；移动或删除工具链后，需要重新核对 Codex 中 Mobile MCP 的绝对路径。此前的 Android 35/36 镜像、AVD、测试日志和 APK 不在当前检出中，不能声称已经恢复。

### 后续：用户授权连接真机并安装 APK

手机已允许 USB 调试，识别为 REDMI K90 Ultra / Android 17；安装前未检测到 `com.radium.inkwell`。用户授权安装 APK，来源由用户手动导入。

首次 `./gradlew.bat --no-configuration-cache --max-workers=2 assembleDebug` 下载依赖较慢，Kotlin 编译器网络读取长时间未结束；主动停止 Gradle 后复用缓存重试，没有将首轮记为构建通过。重试命令：

```powershell
. .\tools\Use-DevEnvironment.ps1
.\gradlew.bat --no-daemon --info --no-configuration-cache --max-workers=4 assembleDebug
```

重试结果：`BUILD SUCCESSFUL in 6m 41s`，99 项任务（34 执行、65 up-to-date）；日志 `tmp/toolchain/phone-debug-build-retry.log`，首轮日志 `tmp/toolchain/phone-debug-build.log`。本轮没有修改应用源码，没有运行单元测试、仪器测试或真实站点登录测试。

- APK：`app/build/outputs/apk/debug/app-debug.apk`，94,715,926 字节。
- 包名 `com.radium.inkwell`，版本 `0.5.9`，`debuggable=true`；由 `apkanalyzer manifest` 实际核对。
- SHA-256：`59ef6a14d186f85e4c68d40ce90fa784f960d0fa87c14bb6ff5679fc064ddbdd`。
- `apksigner verify --print-certs` 成功；本机调试证书 SHA-256：`dc4be765488b0e7112458741486a81902a0aa5db22bc9d69f2783613d5494772`。这是本机新生成的调试证书，后续更新须保留对应密钥，不假定与旧开发电脑的证书一致。
- 首次通过 Mobile MCP 安装被手机拒绝：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。当前安装状态为 BLOCKED，已请用户检查「USB 安装」并允许安装确认；未卸载、未清空数据，也未将安装拒绝当作应用启动或登录失败。

本地安装证据：`tmp/toolchain/phone-install-evidence.json`。

用户处理 USB 安装限制并明确允许重试后，Mobile MCP 安装成功；随后启动 `com.radium.inkwell` 成功。设备端实际版本为 `0.5.9` / `509999`，`DEBUGGABLE` 标志存在，前台为 `IconClassic`。已读到应用界面层级。用户报告已手动导入来源并打开「大厂正版」分组；原登录校验问题仍待该设备逐项复现，安装和启动成功不代表认证通过。未清空或卸载应用，未执行真实账号输入。
