# 在当前 PowerShell 会话启用项目本地工具链：. .\tools\Use-DevEnvironment.ps1
$ErrorActionPreference = 'Stop'
$inkwellProjectRoot = Split-Path $PSScriptRoot -Parent
$inkwellToolchainRoot = Join-Path $inkwellProjectRoot 'tmp/toolchain'
$inkwellJdk = @(Get-ChildItem (Join-Path $inkwellToolchainRoot 'jdk') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') })
if ($inkwellJdk.Count -ne 1) {
    throw '项目 tmp/toolchain/jdk 下应有且只有一套 JDK 21。'
}
$inkwellSdk = Join-Path $inkwellToolchainRoot 'android-sdk'
if (!(Test-Path (Join-Path $inkwellSdk 'cmdline-tools/latest/bin/sdkmanager.bat'))) {
    throw '项目本地 Android SDK 命令行工具尚未安装。'
}

# 仅设置本进程环境，不改系统 PATH，避免影响其它项目。
$env:JAVA_HOME = $inkwellJdk[0].FullName
$env:ANDROID_HOME = $inkwellSdk
$env:ANDROID_SDK_ROOT = $inkwellSdk
$env:ANDROID_USER_HOME = Join-Path $inkwellToolchainRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $inkwellToolchainRoot 'avd'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:GRADLE_USER_HOME = Join-Path $inkwellToolchainRoot 'gradle-cache'
$inkwellToolPaths = @(
    (Join-Path $env:JAVA_HOME 'bin'),
    (Join-Path $inkwellSdk 'cmdline-tools/latest/bin'),
    (Join-Path $inkwellSdk 'platform-tools')
)
$env:PATH = (($inkwellToolPaths + @($env:PATH -split ';') | Select-Object -Unique) -join ';')
