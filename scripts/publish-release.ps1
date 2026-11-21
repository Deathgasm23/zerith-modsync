<#
.SYNOPSIS
  Publish a modpack version: build ModSync, validate all JARs, generate the manifest, create a NEW GitHub
  Release (draft -> verified -> published), and only then commit/push modpack/modpack.json.
.DESCRIPTION
  Never overwrites a release, never force-pushes, never deletes. If anything fails before the final push,
  the previously published manifest stays untouched. Use -DryRun to do everything except talk to GitHub.
.EXAMPLE
  .\scripts\publish-release.ps1 -Owner "YOUR_USERNAME" -Repo "zerith-modsync" -Version "1.0.1" -ModsDirectory "C:\Modpack\mods"
#>
param(
    [Parameter(Mandatory)][string]$Owner,
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$ModsDirectory,
    [string]$Branch = "main",
    [string]$PackName = "Zerith Modpack",
    [switch]$SkipBuild,
    [switch]$DryRun
)
$ErrorActionPreference = 'Continue'   # native stderr must not abort; exit codes are checked explicitly
$PSDefaultParameterValues['*:ErrorAction'] = 'Stop'
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
function Fail($m) { Write-Host "`nPUBLISH FAILED: $m" -ForegroundColor Red; Write-Host "The previously published manifest was not changed." -ForegroundColor Yellow; exit 1 }
function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Native { param([string]$Exe, [string[]]$A) & $Exe @A; if ($LASTEXITCODE -ne 0) { Fail "$Exe $($A -join ' ') (exit $LASTEXITCODE)" } }

if ($Owner -match '^(OWNER|YOUR_USERNAME)$' -or $Owner -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}$') { Fail "Owner '$Owner' is a placeholder or invalid" }
if ($Repo -notmatch '^[A-Za-z0-9._-]{1,100}$')      { Fail "Invalid repository name" }
if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]{0,40}$') { Fail "Invalid version '$Version'" }
if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) { Fail "ModsDirectory not found: $ModsDirectory" }
$Tag = "v$Version"

# ---------- tooling / repository state ----------
Step "Checking tools and repository"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail "git is not installed" }
if (-not $DryRun) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/ and run 'gh auth login'" }
    & gh auth status 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "gh is not authenticated. Run: gh auth login" }
}
& git rev-parse --is-inside-work-tree 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Not inside a git repository (run 'git init' first, see docs/PUBLISHING.md)" }
$cur = (& git rev-parse --abbrev-ref HEAD).Trim()
if ($cur -ne $Branch) { Fail "Current branch is '$cur', expected '$Branch'" }
$dirty = & git status --porcelain
if ($dirty -and -not $DryRun) { Fail "Working tree has uncommitted changes. Commit or stash them first." }
if ($dirty -and $DryRun) { Write-Host "  (dry run: working tree is dirty, ignored)" -ForegroundColor Yellow }
if (-not $DryRun) {
    $remote = (& git remote get-url origin 2>$null)
    if ($LASTEXITCODE -ne 0 -or $remote -notmatch "github\.com[:/]$([regex]::Escape($Owner))/$([regex]::Escape($Repo))(\.git)?$") { Fail "Remote 'origin' ($remote) is not github.com/$Owner/$Repo" }
    Native git @('fetch', 'origin', $Branch)
    $behind = (& git rev-list --count "HEAD..origin/$Branch").Trim()
    if ([int]$behind -gt 0) { Fail "Local branch is $behind commit(s) behind origin/$Branch. Pull first." }
    & gh release view $Tag --repo "$Owner/$Repo" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { Fail "Release $Tag already exists. Releases are immutable; choose a new version." }
}

# ---------- build ----------
$dist = Join-Path $root 'dist'
if (-not $SkipBuild) {
    Step "Building Zerith ModSync (owner=$Owner)"
    & "$PSScriptRoot\build.ps1" -Owner $Owner -Repo $Repo -Branch $Branch
    if ($LASTEXITCODE -ne 0) { Fail "Build failed" }
} else {
    & "$root\gradlew.bat" :manifest-cli:cliJar --console=plain -q
    if ($LASTEXITCODE -ne 0) { Fail "Could not build manifest CLI" }
}
$gp = (Select-String -Path "$root\gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$selfJar = Join-Path $dist "zerith-modsync-$gp.jar"
if (-not (Test-Path $selfJar)) { Fail "Built JAR missing: $selfJar (run without -SkipBuild)" }
$cli = Join-Path $root 'manifest-cli\build\libs\zerith-modsync-cli.jar'

# ---------- assemble + validate ----------
Step "Assembling release folder"
$work = Join-Path $dist "release-$Tag"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null
Get-ChildItem -LiteralPath $ModsDirectory -Force | ForEach-Object {
    if ($_.PSIsContainer -or $_.Extension -ne '.jar') { Fail "ModsDirectory must contain only .jar files; found: $($_.Name)" }
    if ($_.Name -like 'zerith-modsync*') { Write-Host "  skipping '$($_.Name)': the freshly built ModSync JAR is used instead" -ForegroundColor Yellow; return }
    Copy-Item -LiteralPath $_.FullName -Destination $work
}
Copy-Item $selfJar $work
# the manifest lives outside the folder that is validated and uploaded
$manifest = Join-Path $dist "modpack-$Tag.json"
Step "Generating manifest (validates every JAR, computes SHA-256)"
Native java @('-jar', $cli, 'generate', '--mods-dir', $work, '--owner', $Owner, '--repo', $Repo, '--tag', $Tag,
              '--pack-version', $Version, '--pack-name', $PackName, '--output', $manifest)
Native java @('-jar', $cli, 'validate', $manifest, '--mods-dir', $work)
$jars = Get-ChildItem $work -Filter *.jar | ForEach-Object { $_.FullName }
Write-Host ("  {0} JARs, manifest: {1}" -f $jars.Count, $manifest)

if ($DryRun) {
    Write-Host "`nDRY RUN OK - nothing was sent to GitHub." -ForegroundColor Green
    Write-Host "Manifest: $manifest"; Write-Host "Assets:   $work"; exit 0
}

# ---------- GitHub: draft release -> verify -> publish -> manifest last ----------
Step "Creating DRAFT release $Tag and uploading assets"
Native gh (@('release', 'create', $Tag) + $jars + @('--repo', "$Owner/$Repo", '--draft', '--target', $Branch, '--title', "Modpack $Version", '--notes', "Zerith modpack $Version. Assets are referenced by modpack/modpack.json."))

Step "Verifying uploaded assets (names, sizes, SHA-256)"
$verify = Join-Path $dist "verify-$Tag"
if (Test-Path $verify) { Remove-Item -Recurse -Force $verify }
New-Item -ItemType Directory $verify | Out-Null
Native gh @('release', 'download', $Tag, '--repo', "$Owner/$Repo", '--dir', $verify)
foreach ($j in Get-ChildItem $work -Filter *.jar) {
    $d = Join-Path $verify $j.Name
    if (-not (Test-Path $d)) { Fail "Asset missing on GitHub: $($j.Name). Draft release $Tag left in place; delete it manually if you want to retry." }
    if ((Get-FileHash $d -Algorithm SHA256).Hash -ne (Get-FileHash $j.FullName -Algorithm SHA256).Hash) { Fail "Asset hash mismatch on GitHub: $($j.Name). Draft release $Tag left in place." }
}
Step "Publishing release $Tag"
Native gh @('release', 'edit', $Tag, '--repo', "$Owner/$Repo", '--draft=false')

Step "Committing manifest (last step)"
New-Item -ItemType Directory -Force (Join-Path $root 'modpack') | Out-Null
Copy-Item $manifest (Join-Path $root 'modpack\modpack.json') -Force
Native git @('add', 'modpack/modpack.json')
& git diff --cached --quiet
if ($LASTEXITCODE -eq 0) { Write-Host "  manifest unchanged, nothing to commit" } else {
    Native git @('commit', '-m', "Publish modpack $Version")
    Native git @('push', 'origin', $Branch)
}

Step "Verifying public manifest URL"
$url = "https://raw.githubusercontent.com/$Owner/$Repo/$Branch/modpack/modpack.json"
$want = (Get-FileHash $manifest -Algorithm SHA256).Hash
$ok = $false
for ($i = 0; $i -lt 30 -and -not $ok; $i++) {
    try {
        $tmpf = Join-Path $verify 'public.json'
        Invoke-WebRequest -Uri "$url?cb=$([guid]::NewGuid())" -OutFile $tmpf -UseBasicParsing
        $ok = (Get-FileHash $tmpf -Algorithm SHA256).Hash -eq $want
    } catch { }
    if (-not $ok) { Start-Sleep -Seconds 10 }
}
if (-not $ok) { Write-Host "WARNING: public manifest did not match after 5 min (CDN cache?). Check $url manually." -ForegroundColor Yellow; exit 2 }
Write-Host "`nPUBLISH OK: $Tag  ($($jars.Count) assets)" -ForegroundColor Green
Write-Host "Manifest URL: $url"
