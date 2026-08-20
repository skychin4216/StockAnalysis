# ═══════════════════════════════════════════════════════════════════
# publish_update.ps1 - StockAnalysis release & auto-publish script
#
# Flow:
#   1. Read versionCode/versionName from app/build.gradle.kts (or override via args)
#   2. Run  :app:assembleRelease  to build the Release APK
#   3. Copy APK into release/output/
#   4. Generate update manifest release/output/latest.json
#   5. Auto-upload to Tencent Cloud COS (preferred) or via scp
#
# Tencent COS upload requires:  release/cos_config.json  (see cos_config.example.json)
#   {
#     "secret_id":     "AKID...",
#     "secret_key":    "...",
#     "bucket":        "stock-update-1250000000",
#     "region":        "ap-guangzhou",
#     "remote_prefix": "update",
#     "public_base":   "https://stock-update-1250000000.cos.ap-guangzhou.myqcloud.com"
#   }
#   It auto-installs `coscmd` via pip when missing.
#   NOTE: make the bucket publicly readable (or use CDN) so phones can download the APK.
#
# Usage:
#   .\release\publish_update.ps1                                # build + upload to COS (if config exists)
#   .\release\publish_update.ps1 -Changelog "Fix bugs"          # build + upload with changelog
#   .\release\publish_update.ps1 -Force                         # force update
#   .\release\publish_update.ps1 -NoUpload                      # build only, no upload
#   .\release\publish_update.ps1 -UploadTarget "user@host:/var/www/update/"   # scp instead of COS
#   .\release\publish_update.ps1 -VersionCode 3 -VersionName "1.2.0"          # override version
#
# Requirements:
#   - JDK + Android SDK (local.properties configured)
#   - Release signing: place keystore.properties at project root (see keystore.properties.example).
#     Without it, the release build falls back to debug signing (internal testing only).
# ═══════════════════════════════════════════════════════════════════

param(
    # APK download URL written into latest.json (auto-built from COS public_base when empty)
    [string]$ApkUrl = "",
    # Update changelog
    [string]$Changelog = "",
    # Force update flag
    [switch]$Force,
    # Override versionCode/versionName (0/empty = read from build.gradle.kts)
    [int]$VersionCode = 0,
    [string]$VersionName = "",
    # scp target like "user@host:/var/www/update/" (used when COS config is absent)
    [string]$UploadTarget = "",
    # Skip upload step (build + generate manifest only)
    [switch]$NoUpload,
    # Path to COS config json (default: release/cos_config.json)
    [string]$CosConfig = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot  = Split-Path -Parent $PSScriptRoot
$Gradlew     = Join-Path $ProjectRoot "gradlew.bat"
$OutDir      = Join-Path $ProjectRoot "release\output"
$BuildGradle = Join-Path $ProjectRoot "app\build.gradle.kts"
if ([string]::IsNullOrWhiteSpace($CosConfig)) {
    $CosConfig = Join-Path $ProjectRoot "release\cos_config.json"
}

# ── 1. Read version from build.gradle.kts ────────────────────────────
function Read-VersionFromGradle {
    $content = Get-Content $BuildGradle -Raw
    $vc = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
    $vn = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
    return @{
        Code = if ($vc.Success) { [int]$vc.Groups[1].Value } else { 1 }
        Name = if ($vn.Success) { $vn.Groups[1].Value } else { "1.0" }
    }
}

$ver = Read-VersionFromGradle
if ($VersionCode -le 0) { $VersionCode = $ver.Code }
if ([string]::IsNullOrWhiteSpace($VersionName)) { $VersionName = $ver.Name }

$hasCosConfig = Test-Path $CosConfig
$ApkFileName  = "StockAnalysis-v$VersionName.apk"

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  StockAnalysis Publish Tool" -ForegroundColor Cyan
Write-Host "  Version: $VersionName (versionCode=$VersionCode)"
Write-Host "  Upload : $(if ($NoUpload) { 'NONE (build only)' } elseif ($hasCosConfig) { 'Tencent COS' } elseif ($UploadTarget) { 'scp: ' + $UploadTarget } else { 'NONE (no config)' })"
Write-Host "==============================================" -ForegroundColor Cyan

# ── 2. Build Release APK ─────────────────────────────────────────────
Write-Host "`n[1/4] Building Release APK ..." -ForegroundColor Yellow
if (-not (Test-Path $Gradlew)) { throw "gradlew.bat not found: $Gradlew" }
Push-Location $ProjectRoot
try {
    & $Gradlew :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease FAILED (exit=$LASTEXITCODE)" }
} finally {
    Pop-Location
}

$ApkSource = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $ApkSource)) { throw "APK not found: $ApkSource" }

# ── 3. Copy APK into release/output ──────────────────────────────────
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$ApkFile = Join-Path $OutDir $ApkFileName
Copy-Item $ApkSource $ApkFile -Force
Write-Host "APK copied: $ApkFile" -ForegroundColor Green

# ── 4. Generate latest.json ──────────────────────────────────────────
if ([string]::IsNullOrWhiteSpace($Changelog)) { $Changelog = "Version $VersionName" }

# Determine download URL: explicit arg > COS public URL > placeholder
if ([string]::IsNullOrWhiteSpace($ApkUrl)) {
    if ($hasCosConfig) {
        try {
            $cos = Get-Content $CosConfig -Raw | ConvertFrom-Json
            $base = $cos.public_base.TrimEnd('/')
            $prefix = if ([string]::IsNullOrWhiteSpace($cos.remote_prefix)) { "" } else { $cos.remote_prefix.Trim('/') }
            $remotePath = if ($prefix) { "$prefix/$ApkFileName" } else { $ApkFileName }
            $ApkUrl = "$base/$remotePath"
        } catch {
            throw "Failed to parse COS config $CosConfig : $_"
        }
    } else {
        $ApkUrl = "https://YOUR-SERVER/update/$ApkFileName"
        Write-Host "WARN: no -ApkUrl and no COS config; latest.json uses a placeholder URL" -ForegroundColor Yellow
    }
}

$json = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    url         = $ApkUrl
    changelog   = $Changelog
    force       = [bool]$Force
} | ConvertTo-Json

$JsonFile = Join-Path $OutDir "latest.json"
Set-Content -Path $JsonFile -Value $json -Encoding UTF8
Write-Host "Manifest generated: $JsonFile" -ForegroundColor Green
Write-Host "`n$json"

# ── 5. Upload ────────────────────────────────────────────────────────
if ($NoUpload) {
    Write-Host "`n[4/4] Upload skipped (-NoUpload)" -ForegroundColor Yellow
} elseif ($hasCosConfig) {
    Write-Host "`n[4/4] Uploading to Tencent COS ..." -ForegroundColor Yellow

    $cos = Get-Content $CosConfig -Raw | ConvertFrom-Json
    $prefix = if ([string]::IsNullOrWhiteSpace($cos.remote_prefix)) { "" } else { $cos.remote_prefix.Trim('/') }

    # Ensure coscmd is installed
    $coscmd = Get-Command coscmd -ErrorAction SilentlyContinue
    if (-not $coscmd) {
        Write-Host "  coscmd not found, installing via pip ..." -ForegroundColor Yellow
        & pip install coscmd --quiet
        if ($LASTEXITCODE -ne 0) { throw "pip install coscmd FAILED" }
    }

    # Configure coscmd (writes ~/.cos.conf)
    & coscmd config -a $cos.secret_id -s $cos.secret_key -b $cos.bucket -r $cos.region
    if ($LASTEXITCODE -ne 0) { throw "coscmd config FAILED" }

    # Upload APK
    $apkRemote = if ($prefix) { "$prefix/$ApkFileName" } else { $ApkFileName }
    & coscmd upload $ApkFile $apkRemote
    if ($LASTEXITCODE -ne 0) { throw "coscmd upload APK FAILED" }

    # Upload manifest
    $jsonRemote = if ($prefix) { "$prefix/latest.json" } else { "latest.json" }
    & coscmd upload $JsonFile $jsonRemote
    if ($LASTEXITCODE -ne 0) { throw "coscmd upload latest.json FAILED" }

    $ManifestUrl = if ($prefix) { "$($cos.public_base.TrimEnd('/'))/$prefix/latest.json" } else { "$($cos.public_base.TrimEnd('/'))/latest.json" }
    Write-Host "  Upload OK" -ForegroundColor Green
    Write-Host "  APK URL : $ApkUrl"
    Write-Host "  Manifest: $ManifestUrl"
} elseif (-not [string]::IsNullOrWhiteSpace($UploadTarget)) {
    Write-Host "`n[4/4] Uploading via scp to $UploadTarget ..." -ForegroundColor Yellow
    scp $ApkFile "$UploadTarget"
    scp $JsonFile "$UploadTarget"
    if ($LASTEXITCODE -ne 0) { throw "scp upload FAILED" }
    Write-Host "  Upload OK" -ForegroundColor Green
} else {
    Write-Host "`n[4/4] Upload skipped: no COS config / no scp target" -ForegroundColor Yellow
    Write-Host "  Put release/output/* onto your server manually." -ForegroundColor Yellow
}

# ── 6. Summary ───────────────────────────────────────────────────────
Write-Host "`n==============================================" -ForegroundColor Cyan
Write-Host "  Publish done!" -ForegroundColor Green
Write-Host "  - APK   : $ApkFile"
Write-Host "  - Manifest: $JsonFile"
Write-Host "  - Version: $VersionName ($VersionCode)"
Write-Host "  - Download URL: $ApkUrl"
Write-Host ""
Write-Host "  In-app setup (one-time):"
if ($hasCosConfig -and -not $NoUpload) {
    Write-Host "   1. In the app: Settings > Update > paste this Manifest URL:"
    Write-Host "      $ManifestUrl"
    Write-Host "   2. Or edit assets/data/app_config.json -> update.manifest_url"
} else {
    Write-Host "   1. Host release/output/latest.json + APK on your server"
    Write-Host "   2. In the app: Settings > Update > paste the latest.json URL"
}
Write-Host "==============================================" -ForegroundColor Cyan
