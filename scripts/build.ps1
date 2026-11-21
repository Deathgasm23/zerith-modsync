<#
.SYNOPSIS  Test + build the production (remapped) Zerith ModSync JAR and copy it to dist/.
.PARAMETER Owner   GitHub user baked into the default manifest URL (default: value in gradle.properties, i.e. OWNER placeholder)
.PARAMETER Repo    Repository name (default zerith-modsync)
.PARAMETER SkipTests  Skip `gradlew test` (build still runs the unit tests unless you pass this)
#>
param(
    [string]$Owner = "",
    [string]$Repo = "",
    [string]$Branch = "",
    [switch]$SkipTests
)
$ErrorActionPreference = 'Continue'   # native stderr must not abort; exit codes are checked explicitly
$PSDefaultParameterValues['*:ErrorAction'] = 'Stop'
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# --- JDK check (Gradle toolchains pick JDK 21 for compilation; Gradle itself needs 17+) ---
$jv = "$(& java -version 2>&1 | Select-Object -First 1)"
if ($LASTEXITCODE -ne 0 -or -not $jv) { Fail "java not found. Install JDK 21 and set JAVA_HOME." }
if ($jv -notmatch '"(\d+)') { Fail "Cannot parse java version: $jv" }
if ([int]$Matches[1] -lt 21) { Fail "JDK 21+ required, found: $jv" }
Write-Host "JDK: $jv"

$props = @()
if ($Owner)  { $props += "-PghOwner=$Owner" }
if ($Repo)   { $props += "-PghRepo=$Repo" }
if ($Branch) { $props += "-PghBranch=$Branch" }

$tasks = @('clean')
if ($SkipTests) { $tasks += @('build', '-x', 'test') } else { $tasks += 'build' }
$tasks += ':manifest-cli:cliJar'
& "$root\gradlew.bat" @tasks @props --console=plain
if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed (exit $LASTEXITCODE)" }

$version = (Select-String -Path "$root\gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$jar = Join-Path $root "build\libs\zerith-modsync-$version.jar"
if (-not (Test-Path $jar)) { Fail "Production JAR not found: $jar" }

# --- verify metadata inside the JAR ---
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $names = $zip.Entries | ForEach-Object { $_.FullName }
    foreach ($need in 'fabric.mod.json', 'worker/worker.jar', 'zerith-modsync-build.properties', 'com/zerith/modsync/ZerithModSyncClient.class') {
        if ($names -notcontains $need) { Fail "JAR is missing $need" }
    }
    if ($names -contains 'com/zerith/modsync/ZerithModSyncClient.class' -and ($names | Where-Object { $_ -like '*.mixins.json' })) { }
    $sr = New-Object System.IO.StreamReader(($zip.GetEntry('fabric.mod.json')).Open())
    $meta = $sr.ReadToEnd() | ConvertFrom-Json; $sr.Close()
    if ($meta.id -ne 'zerith-modsync') { Fail "Unexpected mod id: $($meta.id)" }
    if ($meta.version -ne $version)    { Fail "fabric.mod.json version '$($meta.version)' != $version (resources not expanded)" }
    if ($names -contains 'net/minecraft/client/MinecraftClient.class') { Fail "Unexpected Minecraft classes in JAR" }
    # remapped (intermediary) check: yarn names must not appear in the client class
    $sr = New-Object System.IO.StreamReader(($zip.GetEntry('com/zerith/modsync/ZerithModSyncClient.class')).Open())
    $ms = New-Object System.IO.MemoryStream; $sr.BaseStream.CopyTo($ms); $sr.Close()
    $txt = [System.Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray())
    if ($txt -notmatch 'net/minecraft/class_\d+') { Fail "Client class is not remapped to intermediary (dev JAR?)" }
} finally { $zip.Dispose() }

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $jar $dist -Force
$out = Join-Path $dist (Split-Path $jar -Leaf)
$sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host "BUILD OK"
Write-Host "JAR:    $out"
Write-Host "SHA256: $sha"
