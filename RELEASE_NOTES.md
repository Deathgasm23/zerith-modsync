# Release notes

## 1.0.0
- Fabric client mod for Minecraft 1.21.1: GitHub manifest sync with exact `mods` replacement after exit.
- Bundled external worker with journal, backup, rollback and recovery; self-update through the manifest.
- Manifest generator/validator CLI (`generateManifest`, `validateManifest`), build/publish/validate scripts for PowerShell and Bash.
- Not included: manifest signatures, in-game screens (chat/action-bar messages only).
- Unverified in this release: behaviour inside a real Minecraft client, GitHub publication via `gh` (see `IMPLEMENTATION_CHECKLIST.md`).
