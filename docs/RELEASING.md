# Releasing

Releases are built and published by GitHub Actions ([`release.yml`](../.github/workflows/release.yml)).

## Cutting a release

1. Add release notes for the new version to `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
   The versionCode is derived from the version: `1.2.3` → `10203`, `0.1.0` → `100`.
2. Start the build, either way:
   - **Actions → Release → Run workflow**, enter the version (e.g. `0.2.0`), or
   - push a tag: `git tag v0.2.0 && git push origin v0.2.0`.

The workflow runs the unit tests, builds a signed APK and creates the GitHub Release with the APK and the changelog as notes. Versions with a suffix (`0.2.0-beta.1`) become pre-releases. Running the workflow without a version gives a test build (debug-signed unless the secrets exist) attached to the run.

Releases refuse to build without the signing secrets, so an unsigned APK can never replace a signed one.

## Installing

Download `calendar-bridge-<version>.apk` from the GitHub Release and open it on the phone, or:

```bash
adb install -r calendar-bridge-0.1.0.apk
```

An install signed with a different key (for example a debug build from Android Studio) has to be uninstalled first. Uninstalling removes the app's calendars; they come back on the next sync after re-adding the URLs.

## Signing

Release builds are signed with the key from these repository secrets:

| Secret | Value |
|---|---|
| `CALBRIDGE_KEYSTORE_BASE64` | `base64 -i calendar-bridge.jks` |
| `CALBRIDGE_KEYSTORE_PASSWORD` | keystore password |
| `CALBRIDGE_KEY_ALIAS` | key alias |
| `CALBRIDGE_KEY_PASSWORD` | key password |

Keep the keystore backed up outside the repository. Every release must be signed with the same key, or existing installs can't update.

## Google Play

Not set up: Calendar Bridge is installed from GitHub Releases.
