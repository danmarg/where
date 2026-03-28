{
  description = "Where — iOS/Android/Server dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "35.0.0" ];
          platformVersions = [ "35" ];
          abiVersions = [ "arm64-v8a" "x86_64" ];
          includeEmulator = false;
          includeSources = false;
          includeSystemImages = false;
          useGoogleAPIs = false;
          extraLicenses = [
            "android-sdk-license"
            "android-sdk-preview-license"
          ];
        };

        androidSdk = androidComposition.androidsdk;

        jdk = pkgs.jdk21;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            jdk
            pkgs.gradle
            pkgs.kotlin
            pkgs.ktlint
            pkgs.xcodegen
            pkgs.gh
            androidSdk
          ];

          JAVA_HOME = "${jdk}";

          shellHook = ''
            export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
            export SDKROOT=/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk
            # Prepend real Xcode tools so they shadow Nix stubs (xcrun, lipo, etc.)
            export PATH=/Applications/Xcode.app/Contents/Developer/usr/bin:/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin:$PATH
            # Android SDK/AVD in home directory, fallback to nix-store
            export ANDROID_HOME=''${ANDROID_HOME:-''${HOME}/.android/sdk}
            [ ! -d "$ANDROID_HOME" ] && export ANDROID_HOME=${androidSdk}/libexec/android-sdk
            export ANDROID_SDK_ROOT=''${ANDROID_SDK_ROOT:-$ANDROID_HOME}
            export ANDROID_AVD_HOME=''${ANDROID_AVD_HOME:-$HOME/.android/avd}
            export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
            # Ensure TMPDIR is set properly for nix
            export TMPDIR=''${TMPDIR:-/tmp}
            # Write JDK path for Xcode build scripts (which run outside the Nix shell)
            echo "$JAVA_HOME" > .xcode-java-home
            echo "Where dev environment"
            echo "  Java:    $(java -version 2>&1 | head -1)"
            echo "  Kotlin:  $(kotlin -version 2>&1)"
            echo "  Gradle:  $(gradle --version 2>&1 | grep '^Gradle')"
            echo "  Android: $ANDROID_HOME"
            echo "  Xcode:   $(xcodebuild -version 2>&1 | head -1)"
          '';
        };
      }
    );
}
