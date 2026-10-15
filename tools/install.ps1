# Builds Nudge and installs it on the phone (USB) and/or the watch (wireless debugging).
#
#   .\tools\install.ps1                      # phone only, over USB
#   .\tools\install.ps1 -Watch 192.168.1.50:41234   # phone + watch
#   .\tools\install.ps1 -Watch 192.168.1.50:41234 -SkipPhone
#   .\tools\install.ps1 -NoBuild ...         # reuse the last build
#
# The accurate speech model (Parakeet, ~630 MB) is copied to the phone once, next to the app's
# data, instead of living in the APK; later installs skip it when it's already there.
# Uninstalling the app deletes it; run this again to put it back.
param(
    [string]$Watch,
    [switch]$SkipPhone,
    [switch]$NoBuild
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

# Optional machine settings from local.properties: nudge.buildDir, nudge.javaHome.
$local = @{}
$lp = Join-Path $root "local.properties"
if (Test-Path $lp) {
    Get-Content $lp | Where-Object { $_ -match "^\s*([\w.]+)\s*=\s*(.+)$" } | ForEach-Object { $local[$Matches[1]] = $Matches[2].Trim() }
}
function Apk([string]$module) {
    $base = if ($local["nudge.buildDir"]) { Join-Path $local["nudge.buildDir"] $module } else { Join-Path $root "$module\build" }
    Join-Path $base "outputs\apk\release\$module-release.apk"
}

& (Join-Path $PSScriptRoot "fetch-models.ps1")

if (-not $NoBuild) {
    if ($local["nudge.javaHome"]) { $env:JAVA_HOME = $local["nudge.javaHome"] }
    if (-not $env:JAVA_HOME) {
        # Look for a JDK 17-21 where IntelliJ/Android Studio download them.
        $jdk = Get-ChildItem (Join-Path $HOME ".jdks") -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -match "(^|-)(21|17)\." | Select-Object -First 1
        if (-not $jdk) { throw "Set JAVA_HOME to a JDK 17-21 (or nudge.javaHome in local.properties)." }
        $env:JAVA_HOME = $jdk.FullName
    }
    Push-Location $root
    try {
        .\gradlew.bat :mobile:assembleRelease :wear:assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    } finally { Pop-Location }
}

if (-not $SkipPhone) {
    $phone = (& $adb devices) | Select-String "^(\S+)\s+device$" | ForEach-Object { $_.Matches[0].Groups[1].Value } |
        Where-Object { $_ -notmatch ":" -and $_ -notmatch "^emulator" } | Select-Object -First 1
    if (-not $phone) { throw "No phone on USB. Plug it in and allow USB debugging." }
    Write-Host "Installing on phone $phone..."
    & $adb -s $phone install -r (Apk "mobile")

    $remoteBase = "/sdcard/Android/data/app.nudge/files/models"
    foreach ($m in Get-ChildItem (Join-Path $root "models") -Directory | Where-Object Name -like "parakeet-*") {
        $enc = Join-Path $m.FullName "encoder.int8.onnx"
        $remoteSize = (& $adb -s $phone shell "stat -c %s $remoteBase/$($m.Name)/encoder.int8.onnx 2>/dev/null").Trim()
        if ($remoteSize -eq "$((Get-Item $enc).Length)") { Write-Host "Model $($m.Name) already on phone."; continue }
        Write-Host "Copying model $($m.Name) to phone (one time)..."
        & $adb -s $phone shell "mkdir -p $remoteBase/$($m.Name)"
        foreach ($f in Get-ChildItem $m.FullName -File) {
            & $adb -s $phone push $f.FullName "$remoteBase/$($m.Name)/$($f.Name)" | Out-Null
        }
    }
    # Restart so the helper service loads the model.
    & $adb -s $phone shell "am force-stop app.nudge; am start -n app.nudge/.ui.MainActivity" | Out-Null
}

if ($Watch) {
    & $adb connect $Watch | Out-Host
    Write-Host "Installing on watch $Watch..."
    & $adb -s $Watch install -r (Apk "wear")
}
Write-Host "Done."
