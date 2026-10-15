# Downloads the offline speech engine and model (not kept in git: ~700 MB).
#   sherpa-onnx Android library  -> third_party/sherpa-onnx-1.13.8.aar  (~50 MB)
#   Parakeet TDT 0.6B v2 (int8)  -> models/parakeet-0.6b/  copied to the phone by install.ps1 (~630 MB)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path $PSScriptRoot -Parent
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

$aar = Join-Path $root "third_party\sherpa-onnx-1.13.8.aar"
if (-not (Test-Path $aar)) {
    New-Item -ItemType Directory -Force (Split-Path $aar) | Out-Null
    Write-Host "Downloading sherpa-onnx library..."
    Invoke-WebRequest "$base/v1.13.8/sherpa-onnx-1.13.8.aar" -OutFile $aar
}

function Get-Model([string]$archive, [string]$dest, [string]$pattern) {
    if (Test-Path (Join-Path $dest "encoder.int8.onnx")) { return }
    $tmp = Join-Path $env:TEMP "nudge-model-$(Get-Random)"
    New-Item -ItemType Directory -Force $tmp, $dest | Out-Null
    Write-Host "Downloading $archive..."
    $tar = Join-Path $tmp "m.tar.bz2"
    Invoke-WebRequest "$base/asr-models/$archive.tar.bz2" -OutFile $tar
    tar -xjf $tar -C $tmp
    $src = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
    foreach ($part in "encoder", "decoder", "joiner") {
        $f = Get-ChildItem $src -Filter "$part*$pattern" | Select-Object -First 1
        Copy-Item $f.FullName (Join-Path $dest "$part.int8.onnx")
    }
    Copy-Item (Join-Path $src "tokens.txt") $dest
    Remove-Item -Recurse -Force $tmp
}

Get-Model "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8" (Join-Path $root "models\parakeet-0.6b") "*.int8.onnx"
Write-Host "Models ready."
