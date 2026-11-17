# 仅操作独立仪器测试 APK，不清理或卸载用户的正式阅读应用。
param([string]$Serial = 'emulator-5558')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Use-DevEnvironment.ps1"
& "$inkwellProjectRoot/gradlew.bat" :source-auth:assembleDebugAndroidTest
if ($LASTEXITCODE -ne 0) { throw '仪器测试 APK 构建失败' }
adb -s $Serial install -r "$inkwellProjectRoot/source-auth/build/outputs/apk/androidTest/debug/source-auth-debug-androidTest.apk"
if ($LASTEXITCODE -ne 0) { throw '仪器测试 APK 安装失败' }
foreach ($phase in @('prepare', 'restore')) {
    if ($phase -eq 'restore') { adb -s $Serial shell am force-stop com.radium.inkwell.sourceauth.test }
    $result = adb -s $Serial shell am instrument -w -e class com.radium.inkwell.sourceauth.LoginWebSessionTest -e authRestart $phase com.radium.inkwell.sourceauth.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $result | Set-Content -LiteralPath "$inkwellProjectRoot/tmp/auth-restart-$phase.log"
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \([1-9][0-9]* tests\)') { throw "重启验证失败：$phase" }
}
