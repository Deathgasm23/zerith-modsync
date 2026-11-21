# Publishing the modpack

One GitHub repository holds the source, the public manifest (`modpack/modpack.json`) and, as Release assets, the JARs. Replace `YOUR_USERNAME` everywhere.

## 1. Local build and tests
```powershell
.\gradlew.bat clean build
.\scripts\build.ps1 -Owner YOUR_USERNAME
```
```bash
./gradlew clean build && scripts/build.sh --owner YOUR_USERNAME
```

## 2. Git and GitHub (only when the repository does not exist yet)
Check first: `git status` (if it prints a branch, the repo already exists — skip `git init`; check `git remote -v` and do not change remotes).
```powershell
git init
git branch -M main
git add .
git commit -m "Zerith ModSync 1.0.0"
gh auth login
gh auth status
gh repo create YOUR_USERNAME/zerith-modsync --public --source . --remote origin --push
```
Existing empty remote instead of `gh repo create`:
```powershell
git remote add origin https://github.com/YOUR_USERNAME/zerith-modsync.git
git push -u origin main
```
`gh` is the GitHub CLI (https://cli.github.com/). Nothing in this project creates repositories, pushes or publishes by itself except when you run these commands or `publish-release`.

## 3. Publish a modpack version
Put **all** approved Fabric JARs (without ModSync — the freshly built one is added automatically) in one folder, then:
```powershell
.\scripts\publish-release.ps1 -Owner "YOUR_USERNAME" -Repo "zerith-modsync" -Version "1.0.0" -ModsDirectory "C:\Modpack\mods"
```
```bash
scripts/publish-release.sh --owner YOUR_USERNAME --repo zerith-modsync --version 1.0.0 --mods-dir /path/to/mods
```
Add `-DryRun` / `--dry-run` to build, validate and generate the manifest without contacting GitHub.

The script, in order: checks `git`/`gh`/auth, branch `main`, clean tree, `origin`, not behind, release tag unused → builds ModSync with your owner baked in → validates every JAR → generates `dist/modpack-vX.json` → creates a **draft** release and uploads all JARs → downloads and hash-verifies them → publishes the release → commits and pushes `modpack/modpack.json` **last** → checks the raw URL. A failure before the final push leaves the previous manifest untouched; it never overwrites a release, force-pushes or deletes. A leftover draft release after a failure can be deleted by hand.

Later update (e.g. `1.0.1`) = same command with a new `-Version` and the complete new folder of JARs. Players pick it up within 15 minutes of the manifest becoming visible (GitHub's raw CDN can lag a few minutes).

## 4. Verify
```powershell
gh release view v1.0.0 --repo YOUR_USERNAME/zerith-modsync --json assets --jq ".assets[].name"
Invoke-WebRequest https://raw.githubusercontent.com/YOUR_USERNAME/zerith-modsync/main/modpack/modpack.json | Select-Object -ExpandProperty Content
.\scripts\validate-manifest.ps1 -Manifest modpack\modpack.json -ModsDirectory C:\Modpack\mods
```

## 5. Manual manifest generation
```powershell
.\gradlew.bat generateManifest -PmodsDir=C:\Modpack\release-dir -PghOwner=YOUR_USERNAME -PghRepo=zerith-modsync -Ptag=v1.0.0 -PpackVersion=1.0.0 -Poutput=modpack\modpack.json
.\gradlew.bat validateManifest -Pmanifest=modpack\modpack.json
```
The folder must contain only `.jar` files, each a Fabric mod with a unique id, compatible with Minecraft 1.21.1, and exactly one `zerith-modsync` JAR. Output is deterministic (sorted by filename, no timestamps). Asset names are limited to `[A-Za-z0-9._+-]` because GitHub rewrites other characters.

`modpack/modpack.json` in a fresh checkout is a **template** with a placeholder hash; the publish script replaces it.

## 6. Player installation
Copy `zerith-modsync-1.0.0.jar` (built with your owner) to `<instance>\mods`, start the game, restart when told.
