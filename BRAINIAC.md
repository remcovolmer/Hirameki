# Brainiac fork

Brainiac is a personal fork of Hirameki. It builds as its own app, `nl.remcovolmer.brainiac` with the name
Brainiac, so it installs next to Hirameki from Google Play, and it is signed with its own fixed key, so every
release installs over the previous one (for example through Obtainium, which follows the GitHub Releases).

## Branches

- `main` mirrors upstream (`ColbyCabrera/Hirameki`). Do not commit to it.
- `brainiac/main` is the default branch and the base of every release. Merge upstream into it through `main`.

## What differs from upstream

| Where | What |
| --- | --- |
| `gradle.properties` | `brainiacApplicationId`, read by the app and the `api` module |
| `AnkiDroid/build.gradle.kts` | applicationId, app name, `brainiacVersionName` (versionCode follows from it), local signing from `~/.brainiac/keystore/signing.properties` |
| `api/build.gradle.kts`, `api/src/*/AndroidManifest.xml` | content provider authority and permission follow the applicationId, so the app's own provider matches the authority it is installed under |
| `.github/workflows/release_apk.yml` | tags `brainiac-v*`, version from `brainiacVersionName`, APKs named `Brainiac-*` |

Upstream's `baseVersionName` and `baseVersionCode` are left alone, so upstream version bumps merge without
conflicts; the fork never uses them.

## Signing key

The keystore and its passwords live outside every repository, in `~/.brainiac/keystore/`
(`brainiac-release.jks` and `signing.properties`). Back that folder up: Android only installs an update signed
with the same key. The repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`
hold the same key for GitHub Actions.

Keep the key alias (`release-key`) out of every tag and version string. GitHub masks secret values, and it drops
a job output that contains one: with the alias `brainiac`, the tag `brainiac-v0.1.0` never reached the publish
job and `gh release create` failed with an empty tag.

A local release build picks the key up by itself when that file exists:

```
./gradlew :AnkiDroid:assembleFullRelease -Duniversal-apk=true
```

Without the file a local release build falls back to the public test key in `tools/`, and that APK does not
install over a released one.

## Releasing

1. On `brainiac/main`, bump `brainiacVersionName` in `AnkiDroid/build.gradle.kts` (for example `0.1.0` to `0.2.0`).
2. Commit, then tag and push:

   ```
   git tag brainiac-v0.2.0
   git push origin brainiac/main brainiac-v0.2.0
   ```

3. Follow the run: `gh run watch -R remcovolmer/Hirameki`. The release appears on the Releases page when the build
   and the unit tests pass.

Stay on one APK kind on a device (universal or arm64-v8a): the per-ABI versionCodes are higher than the universal one.

## Workflows

Only `release_apk.yml`, `tests_unit.yml` and `lint.yml` are enabled on the fork. The rest (Play Store publishing,
emulator tests, labels, milestones, stale, Open Collective and the like) are disabled with `gh workflow disable`.
