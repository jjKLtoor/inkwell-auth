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
