# Crypto backend: decision record

**Status:** decided (October 2026). **Decision:** keep libsodium, through the
[ionspin multiplatform bindings](https://github.com/ionspin/kotlin-multiplatform-libsodium), pinned
to an exact version. Don't move to HACL\*-backed implementations (e.g. libcrux behind a C shim) for
now; revisit on the triggers below.

## What we use, and where it comes from

| Primitive | JVM / Android | iOS |
|---|---|---|
| X25519, ChaCha20-Poly1305 (IETF), SHA-256/512, CSPRNG | libsodium | libsodium |
| HMAC-SHA-256 | javax `Mac` | CommonCrypto `CCHmac` |
| HKDF-SHA-256 | common Kotlin (`Hkdf.kt`) over the platform HMAC | same |

All of it sits behind `expect`/`actual` in `CryptoPrimitives.kt`, so a backend change touches the
two `CryptoPrimitivesImpl.kt` files and nothing in the protocol.

| Pinned | Version | Where it's set |
|---|---|---|
| Bindings (`com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`) | 0.9.5, `strictly` | `gradle/libs.versions.toml` |
| libsodium on Android | 1.0.20, bundled in the bindings' AAR | follows the bindings |
| libsodium on iOS | statically linked by the bindings | follows the bindings |
| libsodium on desktop JVM (tests, CLI) | the system library (1.0.18 on CI's Ubuntu) | CI's `apt-get install libsodium-dev` |

`strictly` means a transitive dependency can't silently move the version: a conflict fails the
build instead. The desktop JVM isn't a shipped platform; it runs the tests and the CLI.

## How it's checked

- **Wycheproof** (`commonTest/resources/wycheproof`): every X25519, ChaCha20-Poly1305,
  HMAC-SHA-256 and HKDF-SHA-256 case runs in `CryptoPrimitivesVectorTest` on the JVM
  (`:shared:jvmTest`) and on the iOS simulator (`:shared:iosSimulatorArm64Test`, in `ios.yml`),
  covering both HMAC backends. `X25519LowOrderPointTest` runs Wycheproof's zero-shared-secret
  public keys through `dh()` and both handshake messages.
- **Fuzzing** (`docs/fuzzing.md`): every parser of attacker-controlled bytes, nightly.

The first Wycheproof run found that the bindings pass key and nonce buffers through unchecked: a
short ChaCha20-Poly1305 nonce was read past its end, and a long one was silently truncated. Our
callers always pass 12 bytes, but `aeadEncrypt`/`aeadDecrypt` now refuse anything else
(`requireAeadKeyAndNonce`). It also showed that the iOS HMAC would have thrown on an empty
message (Kotlin/Native's `addressOf(0)` on an empty array). Both bugs were in our glue code, not
in libsodium.

## Why not HACL\* / libcrux now

- **Verification doesn't reach the bindings.** HACL\* and libcrux prove the primitive arithmetic
  correct and constant-time. libsodium's arithmetic has a long, clean record for exactly the
  algorithms we use. Our real risk was the glue: argument sizes, empty buffers, and error
  mapping. A libcrux move replaces one third-party binding with a C/Rust shim of our own, built
  for four Android ABIs, three iOS targets and desktop, and reached through JNI and cinterop
  that we'd write and maintain ourselves. That's more unverified glue, not less.
- **No algorithm gap yet.** Everything the protocol uses today is in libsodium.
- **Cost.** We'd own the native build matrix, its toolchain updates, and reproducibility for
  F-Droid, all for a team of one.

## Known weaknesses of staying

- One maintainer for the bindings. A stalled release could block a Kotlin upgrade.
- Two HMAC backends per platform, alongside libsodium. Wycheproof covers both, but moving HMAC
  onto libsodium (`crypto_auth_hmacsha256_init`, which takes any key length, as HKDF salts
  need) would drop a backend. That's a reasonable follow-up.
- The desktop JVM's libsodium version comes from the OS, not from us.

## Revisit when

- we add post-quantum key agreement (e.g. hybrid X25519 + ML-KEM). libsodium has no ML-KEM, and
  libcrux has verified ML-KEM, so that is the natural point to switch;
- the bindings go unmaintained, or block a Kotlin or AGP upgrade;
- a libsodium advisory affects a primitive we use and no bindings release follows promptly.

## Upgrading the bindings

Bump `libsodium` in the catalog, then run `:shared:jvmTest`, `:shared:iosSimulatorArm64Test`,
`:shared:fuzzCorpusTest` and `./compat-test.sh`. Note the bundled libsodium versions here (for
Android, `strings libsodium.so | grep '^1\.0\.'` on the AAR's `jni/` libraries).
