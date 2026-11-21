<#
.SYNOPSIS  Validate a manifest (schema, hashes format, URLs, updater present) and optionally check local JAR hashes.
.EXAMPLE   .\scripts\validate-manifest.ps1 -Manifest modpack\modpack.json -ModsDirectory C:\Modpack\mods
#>
param([string]$Manifest = "modpack/modpack.json", [string]$ModsDirectory = "")
$ErrorActionPreference = 'Continue'   # native stderr must not abort; exit codes are checked explicitly
$PSDefaultParameterValues['*:ErrorAction'] = 'Stop'
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$cli = Join-Path $root 'manifest-cli\build\libs\zerith-modsync-cli.jar'
if (-not (Test-Path $cli)) {
    & "$root\gradlew.bat" :manifest-cli:cliJar --console=plain -q
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: could not build manifest CLI" -ForegroundColor Red; exit 1 }
}
$a = @('-jar', $cli, 'validate', $Manifest)
if ($ModsDirectory) { $a += @('--mods-dir', $ModsDirectory) }
& java @a
exit $LASTEXITCODE
