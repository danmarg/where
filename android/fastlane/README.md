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

Build a release AAB (requires signing env vars)

### android deploy

```sh
[bundle exec] fastlane android deploy
```

Build a release AAB and upload to Google Play (internal track)

### android promote_to_closed

```sh
[bundle exec] fastlane android promote_to_closed
```

Promote the internal version to closed testing (alpha)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
