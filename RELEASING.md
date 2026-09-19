# Releasing

Where ships to four places from one `build.sh` entry point: Google Play (internal → closed
testing → production), F-Droid (via a signed GitHub Release + tag), Apple TestFlight, and the
App Store. This doc is the exact sequence for cutting a release.

## 1. Update release notes

Each store reads its "what's new" text from a plain file in the repo, always just *the current
text* — there's no versionCode/build-number bookkeeping to do here, just edit these before you
deploy:

| Store | File |
|---|---|
| Google Play | `android/fastlane/play_metadata/en-US/changelogs/default.txt` |
| F-Droid | `android/src/fdroid/play/release-notes/en-US/default.txt` |
| TestFlight + App Store | `ios/fastlane/metadata/en-US/release_notes.txt` |

Play's and F-Droid's text can legitimately differ (same as their descriptions can — F-Droid's
build has no Google Play Services/Maps), so update whichever apply. Commit these changes; the
Android deploy lane below requires a clean working tree.

If the Play Store or App Store *listing itself* (title, descriptions, screenshots) needs to
change, edit `android/fastlane/play_metadata/en-US/{title,short_description,full_description}.txt`
and `android/src/fdroid/play/listings/en-US/{title,short-description,full-description}.txt` for
Android/F-Droid. iOS listing text (name, subtitle, keywords, screenshots, etc.) is still managed
manually in App Store Connect — `ios/fastlane/metadata/en-US/` only has `release_notes.txt`, and
`release` only uploads whichever files exist there, so nothing else gets touched.

## 2. Android: Google Play

```
./build.sh --deploy-android-internal
```

This is `android fastlane deploy`. It:
- Requires a clean git working tree, then bumps `versionName`/`versionCode` in
  `android/build.gradle.kts` and commits that bump by itself.
- Builds `standardGmsRelease` (no activity recognition) as an AAB.
- Prompts for the keystore password, then uploads to Play's **internal** track.

**Push the version-bump commit** (`git push`) before continuing — F-Droid's release step and
the reproducible-build verification both need it on GitHub.

Once you've tested the internal build, promote it through Play's tracks:

```
./build.sh --deploy-android-promote-alpha        # internal -> closed testing (alpha)
./build.sh --deploy-android-promote-production    # alpha -> production, 100% rollout
```

These are pure track promotions (no new binary, no metadata/changelog re-upload).

## 3. F-Droid

```
./build.sh --deploy-android-github-binaries
```

This is `android fastlane release_github_binaries`. It requires a clean tree (run it right after
step 2's push, before making other changes) and:
- Builds signed `standardGms` (universal) and `standardFdroid` (per-ABI) release APKs.
- Tags `HEAD` as `v<versionName>` and pushes the tag.
- Creates a GitHub Release at that tag and uploads all the APKs as assets.

Nothing else is manual. `fdroiddata/metadata/net.af0.where.yml` has `AutoUpdateMode: Version` and
`UpdateCheckMode: Tags ^v[\d.]+$`, so F-Droid's own CI notices the new tag, updates the `Builds:`
entries, and diffs its own reproducible build against the `standardFdroid` APKs uploaded above —
give it some time (it runs on its own schedule, not on push).

## 4. iOS: TestFlight and App Store

```
./build.sh --deploy-ios-testflight    # ios fastlane beta
./build.sh --deploy-ios-appstore      # ios fastlane release
```

Both bump `CURRENT_PROJECT_VERSION` in `ios/project.yml` (regenerating the Xcode project so it
sticks), build a release IPA, and upload — `beta` to TestFlight with `release_notes.txt` as the
changelog, `release` to App Store Connect with `submit_for_review: false` (it stages the build;
you still submit for review yourself in App Store Connect) and `release_notes.txt` uploaded as
the version's release notes, nothing else touched.

One-time setup, if you haven't already: `cd ios && bundle exec fastlane bootstrap_signing`
(needs `FASTLANE_ASC_KEY_ID`/`FASTLANE_ASC_ISSUER_ID` env vars and an App Store Connect API key
`.p8` dropped in `ios/fastlane/signing/` — see the comment at the top of `ios/fastlane/Fastfile`).

## Credentials reference

| Var / file | Used by |
|---|---|
| Keystore password (prompted) | `--deploy-android-internal`, `--deploy-android-github-binaries` |
| `PLAY_STORE_JSON_KEY` (default: `android/fastlane/signing/where_play_store_api_key.json`) | `--deploy-android-promote-*` |
| `FASTLANE_ASC_KEY_ID`, `FASTLANE_ASC_ISSUER_ID`, `ios/fastlane/signing/AuthKey_*.p8` | both iOS lanes |
| `gh` CLI, authenticated | `--deploy-android-github-binaries` |

None of the above go through GitHub Actions/CI secrets — all signing and store uploads run
locally.

## Server

The server isn't versioned or released alongside the apps — deploy it independently whenever its
own changes are ready:

```
./scripts/deploy-server.sh
```
