# Wycheproof test vectors

Vendored unmodified from [C2SP/wycheproof](https://github.com/C2SP/wycheproof)
(`testvectors_v1/`) at commit `7a325932231bd7a724c791c8b0d65ea4d6d2f435`, under the
Apache License 2.0.

| File | Primitive | Our implementation |
|---|---|---|
| `x25519_test.json` | X25519 | libsodium (both platforms) |
| `chacha20_poly1305_test.json` | ChaCha20-Poly1305 IETF | libsodium (both platforms) |
| `hmac_sha256_test.json` | HMAC-SHA-256 | javax `Mac` (JVM/Android), CommonCrypto `CCHmac` (iOS) |
| `hkdf_sha256_test.json` | HKDF-SHA-256 | common Kotlin over each platform's HMAC |

`CryptoPrimitivesVectorTest` runs every case on the JVM (`:shared:jvmTest`) and on the iOS
simulator (`:shared:iosSimulatorArm64Test`). Kotlin/Native tests can't read resources, so the
build (`:shared:generateWycheproofSources`) compiles these files into commonTest as string
constants.

To update, replace the files with the same names from a newer commit, update the commit above,
and run both test tasks.
