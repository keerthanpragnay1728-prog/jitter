# Releasing Jitter

The steps, in order. Every release follows all of them. None is optional
because the last release went fine.

## The key never changes

Every release is signed with the one release key. Android installs an update
only when it is signed with the same key as the installed app, so a release
signed with any other key cannot be installed over the previous one. The user
has to uninstall first, which deletes their cycle, leases, locks, reminders and
settings.

So the key is never rotated, never regenerated and never replaced by a fresh
one "for now". Losing it means every existing user must uninstall to update.
Keep the keystore and both passwords backed up offline, in at least two places,
and never in this repository (`*.jks`, `*.keystore` and `*.p12` are ignored, and
`ReleaseSigningTest` fails if one is tracked).

F-Droid signs the builds it publishes with its own key unless the build is
reproducible and it publishes the developer signature instead. An F-Droid
install and a GitHub install are otherwise not updatable from each other.
Decide which before submitting to F-Droid, not after.

## Anyone on dev.molasses

The applicationId changed from `dev.molasses` to `org.jitteros.app`. Android
treats that as a different app, whatever key signed it. A device with
`dev.molasses` installed gets a second app, not an update, and nothing moves
across: cycle, leases, locks, reminders, settings, and every grant
(accessibility, usage access, home app, restricted settings) start fresh.

Worse, both installs can run at once, with two accessibility services gating
the same apps and the old one's alarms still firing. So everyone on
`dev.molasses`, debug or not, uninstalls it before installing
`org.jitteros.app`, and loses that data. Tell them before, not after. Step 5
has no previous release to install over for the first `org.jitteros.app`
build; start its data-survival check from the second.

## Testers on debug builds

A debug build is signed with the SDK's debug key, not the release key. A tester
on a debug build cannot install a release build over it. They uninstall once,
which wipes their data, then install the release. Every release after that
installs over the previous one normally. Tell them before, not after.

## Steps

1. **Bump the version in the catalog.** `appVersion` in
   `gradle/libs.versions.toml` is the only place it is written. versionCode is
   derived from it (`MAJOR * 10000 + MINOR * 100 + PATCH`), so it cannot be
   forgotten, only left unbumped. Add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for the new
   code, 500 characters at most. `StoreMetadataTest` fails without it.

2. **Run the checks.** `tools/check-all.sh`, and read the last line. It must be
   `check-all: PASS`. The exit code is not enough once anything is piped.

3. **Run the app module's unit tests.** `./gradlew testDebugUnitTest` must pass.
   check-all does not run it. check-all compiles the tests without the Compose
   compiler, on its own checkout's line endings, so a test can pass there and
   fail here (CLAUDE.md, "What check-all does not run"). A failure here blocks
   the tag.

4. **Build release with the release key.** The four `JITTER_*` properties set in
   `~/.gradle/gradle.properties` or the environment (README, Building), then:

   ```
   ./gradlew clean assembleRelease
   ```

   The build stops and names any missing property rather than producing an
   unsigned or debug-signed APK. Confirm the certificate:

   ```
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

   The SHA-256 certificate digest must match the one recorded below. If it
   does not, stop.

   Release certificate SHA-256: not yet recorded. Record it here from the
   first release build, and never edit it afterwards.

5. **Install over the previous release on a test device, and confirm data
   survives.** The device has the previous release installed and in use:
   tracked apps edited, a lease taken, a lock armed, a reminder pending.

   ```
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   Then check that the edited target list, the lock, the pending reminder and
   the cycle readout are all still there, that the accessibility service is
   still enabled and bound (CFG, Setup, service state), and that the first-run
   guide does not appear. An install that fails with a signature mismatch
   means step 4 used the wrong key.

6. **Check the merged manifest.**

   ```
   apkanalyzer manifest print app/build/outputs/apk/release/app-release.apk
   ```

   Confirm: no `INTERNET`, no `SYSTEM_ALERT_WINDOW`, no `READ_PHONE_STATE`, no
   `QUERY_ALL_PACKAGES`, no `FOREGROUND_SERVICE` of any type, no
   `android:debuggable`. A library can add a permission through manifest merge
   without any change in this repository, and this is the only step that sees
   it.

7. **Commit the Room schema if it changed.** The build writes
   `app/schemas/dev.molasses.data.db.MolassesDatabase/<version>.json`. If
   `git status` shows it new or modified, commit it with the release. A schema
   version bump also needs its migration. No schema JSON has been committed
   yet, so the first release commits version 1.

8. **Tag on main.** Merge the release branch into `main` first. Then:

   ```
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

   The tag is the commit the APK was built from. If anything was committed
   after the build, rebuild from the tag.

9. **Publish on GitHub Releases.** Rename the APK to `jitter-vX.Y.Z.apk`, then:

   ```
   sha256sum jitter-vX.Y.Z.apk > jitter-vX.Y.Z.apk.sha256
   ```

   Create a release from the tag. Attach the APK and the `.sha256` file, paste
   the SHA-256 into the release notes as text as well, and use the changelog
   from step 1 as the body.
