fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android build_debug

```sh
[bundle exec] fastlane android build_debug
```

Build a debug APK

### android build_release

```sh
[bundle exec] fastlane android build_release
```

Build a release AAB without activity recognition (requires signing env vars)

### android build_release_full

```sh
[bundle exec] fastlane android build_release_full
```

Build a release AAB with full activity recognition (requires signing env vars)

### android deploy

```sh
[bundle exec] fastlane android deploy
```

Build standard AAB (no activity recognition) and upload to Google Play (internal track)

### android deploy_full

```sh
[bundle exec] fastlane android deploy_full
```

Build full AAB (with activity recognition) and upload to Google Play (internal track)

### android release_github_binaries

```sh
[bundle exec] fastlane android release_github_binaries
```

Build signed standardGms + standardFdroid release APKs for the current versionName and publish them as assets on a GitHub Release tagged v<versionName>. This is the signed reference binary F-Droid's Reproducible Builds feature diffs its own build against (see Binaries:/AllowedAPKSigningKeys in metadata/net.af0.where.yml upstream). Signing stays local — nothing here touches GitHub Actions or CI secrets. Run this after committing the versionName/versionCode bump (e.g. from deploy/deploy_full) so HEAD is the exact commit you want the tag to point at. Requires signing env vars and an authenticated `gh` CLI.

### android promote_to_closed

```sh
[bundle exec] fastlane android promote_to_closed
```

Promote the internal version to closed testing (alpha)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
