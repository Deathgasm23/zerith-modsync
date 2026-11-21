# Zerith ModSync (agent notes)
- Status of every requirement: `IMPLEMENTATION_CHECKLIST.md` (read that, don't re-audit).
- Build/test with JDK 21: `./gradlew test`; production JAR: `scripts/build.ps1`. Gradle property names `owner`/`repo` clash with Gradle internals, so use `-PghOwner` / `-PghRepo`.
- `src/core/java` is shared by mod, `worker/` and `manifest-cli/` (compiled into each); tests live in `src/test` and also compile worker/CLI sources.
- Never test against a real `mods` folder; tests use `@TempDir`. Do not push, create repos or publish releases without the user's go-ahead.
