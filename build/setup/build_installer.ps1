# Builds the Windows installer for the QFT Demand Notice Generator with
# jpackage (WiX based MSI, bundles its own Java 21 runtime - no Java needed
# on the target machine).
#
# Usage: powershell -File build\setup\build_installer.ps1   (build first:
#        mvn -o package)
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jarPath = Join-Path $repoRoot 'target\qft-demand-notice.jar'
if (!(Test-Path $jarPath)) {
    throw "Jar not found: $jarPath (run 'mvn -o package' first)"
}

# stable upgrade identity so future versions update in place
$upgradeUuid = '7f2e66b1-9c74-4d90-a6d0-608623ba9a41'

$staging = Join-Path $repoRoot 'target\setup\input'
$dest    = Join-Path $repoRoot 'target\setup'
New-Item -ItemType Directory -Force $staging, $dest | Out-Null
Copy-Item $jarPath (Join-Path $staging 'qft-demand-notice.jar') -Force
$icon = Join-Path $PSScriptRoot 'input\app.ico'
if (Test-Path $icon) {
    Copy-Item $icon (Join-Path $staging 'app.ico') -Force
}

# The shaded jar carries the JavaFX classes AND the Windows natives on the
# classpath, so the bundled runtime only needs the JDK modules. (Temurin 21
# ships no JavaFX jmods, so jlink cannot resolve javafx.* modules.)
$modules = 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs'

& jpackage `
    --type msi `
    --dest $dest `
    --name 'QFT Demand Notice Generator' `
    --description 'Generates tier-2 pension demand notices from Publisher letter templates.' `
    --vendor 'QFTL' `
    --app-version 1.0.0 `
    --copyright 'QFTL' `
    --input $staging `
    --main-jar qft-demand-notice.jar `
    --main-class com.qft.demandnotice.Launcher `
    --add-modules $modules `
    --java-options '-Xms64m' `
    --java-options '-Xmx512m' `
    --win-per-user-install `
    --win-dir-chooser `
    --win-menu `
    --win-menu-group 'QFT' `
    --win-shortcut `
    --win-upgrade-uuid $upgradeUuid `
    --icon (Join-Path $staging 'app.ico')

if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

Get-ChildItem $dest -Filter *.msi | ForEach-Object {
    Write-Output ('installer ready: ' + $_.FullName + ' (' + [Math]::Round($_.Length / 1MB, 1) + ' MB)')
}
