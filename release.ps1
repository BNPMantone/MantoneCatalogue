# Mantone Catalogue - release script
# Usage:  powershell -ExecutionPolicy Bypass -File .\release.ps1 -Notes "What changed" [-Bump patch|minor|major]
param(
    [string]$Notes = "Bug fixes and improvements",
    [ValidateSet("patch", "minor", "major")][string]$Bump = "patch"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ---- checks
$repo = ((Get-Content release.properties | Where-Object { $_ -match '^repo=' }) -replace '^repo=', '').Trim()
if (-not $repo -or $repo -eq "OWNER/REPO") { Fail "Open release.properties and set repo=YourGitHubUser/YourRepo" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "GitHub CLI not found. Install it from https://cli.github.com then run: gh auth login" }
if (-not (Test-Path .\keystore.properties)) { Fail "keystore.properties is missing. See keystore.properties.example" }
if (-not (Test-Path .\gradlew.bat)) { Fail "gradlew.bat is missing" }

# ---- bump version (versionCode +1, versionName major.minor.patch)
$gradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
$orig = [IO.File]::ReadAllText($gradleFile)
$code = [int][regex]::Match($orig, 'versionCode\s*=\s*(\d+)').Groups[1].Value + 1
$name = [regex]::Match($orig, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$p = @($name.Split('.') | ForEach-Object { [int]$_ })
while ($p.Count -lt 3) { $p += 0 }
switch ($Bump) {
    "major" { $p[0]++; $p[1] = 0; $p[2] = 0 }
    "minor" { $p[1]++; $p[2] = 0 }
    default { $p[2]++ }
}
$new = $p -join "."
$updated = $orig -replace 'versionCode\s*=\s*\d+', "versionCode = $code" -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$new`""
[IO.File]::WriteAllText($gradleFile, $updated)

# ---- build the signed release APK
Write-Host "Building version $new (build $code)..." -ForegroundColor Cyan
& .\gradlew.bat assembleRelease
$built = "app\build\outputs\apk\release\app-release.apk"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $built)) {
    [IO.File]::WriteAllText($gradleFile, $orig)
    Fail "Build failed. The version number was restored."
}

# ---- collect files
New-Item -ItemType Directory -Force releases | Out-Null
$apk = "releases\MantoneCatalogue-$new.apk"
Copy-Item $built $apk -Force
$json = "releases\version.json"
$meta = [ordered]@{
    versionCode = $code
    versionName = $new
    apkUrl      = "https://github.com/$repo/releases/download/v$new/MantoneCatalogue-$new.apk"
    notes       = $Notes
}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot $json), ($meta | ConvertTo-Json))

# ---- publish (GitHub Release: APK + version.json). Every version stays downloadable.
git add app/build.gradle.kts
git commit -m "Release v$new"
git push
if ($LASTEXITCODE -ne 0) { Fail "git push failed. Make sure this folder is a git repo connected to $repo" }
gh release create "v$new" $apk $json --repo $repo --title "Mantone Catalogue $new" --notes $Notes
if ($LASTEXITCODE -ne 0) { Fail "Publishing the release failed" }

Write-Host ""
Write-Host "Released v$new. Phones will be forced to update on their next launch." -ForegroundColor Green
Write-Host "Download page: https://github.com/$repo/releases/tag/v$new"
