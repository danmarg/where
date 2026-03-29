# Kotlin/Native Cinterop C Source Compilation Issue

## Problem

C crypto functions defined in `src/nativeInterop/cinterop/where_crypto_impl.c` are not being compiled into the iOS Shared framework, resulting in undefined symbol linker errors at app link time.

### Affected Functions
- `where_sha256`
- `where_hmac_sha256`
- `where_aesgcm_encrypt`
- `where_aesgcm_decrypt`
- `where_x25519_keypair`
- `where_x25519_dh`
- `where_ed25519_keypair`
- `where_ed25519_sign`
- `where_ed25519_verify`

### Error
```
ld: symbol(s) not found for architecture arm64:
  "_where_aesgcm_decrypt"
  "_where_aesgcm_encrypt"
  ...
```

## Root Cause

K/N cinterop is designed for **header interop only** — parsing C headers to generate Kotlin bindings. It does not have a built-in mechanism to compile C source files as part of the framework build. C source compilation is expected to be handled by platform-native toolchains (Xcode for iOS).

## Why Tests Pass But App Build Fails

- **PR unit tests**: Run on JVM target, don't require C code compilation → **PASS**
- **iOS app build**: Links Shared framework which is missing C symbols → **FAIL**

## Attempted Solutions

### 1. `-Xcompile-source` extraOpt
```gradle
extraOpts("-Xcompile-source", "src/nativeInterop/cinterop/where_crypto_impl.c")
```
**Result**: Unrecognized option / compilation errors

### 2. `srcs` Property in .def File
```
srcs = where_crypto_impl.c
```
**Result**: Property not recognized / ignored by K/N

### 3. `-Xsource` Flag
```gradle
extraOpts("-Xsource", "src/nativeInterop/cinterop/where_crypto_impl.c")
```
**Result**: Still undefined symbols at link time

### 4. `nativeSources` Gradle Configuration
```gradle
nativeSources {
    srcDir("src/nativeInterop/cinterop")
    include("*.c")
}
```
**Result**: Gradle build script compilation errors (API not available on cinterop block)

### 5. Inline C Implementation in Header
Included full C implementation in `where_crypto.h` with preprocessor guards:
```c
#ifdef WHERE_CRYPTO_IMPLEMENTATION
// ... full implementation ...
#endif
```
Passed `-DWHERE_CRYPTO_IMPLEMENTATION` via `compilerOpts` in .def file.

**Result**: Still undefined symbols; K/N header parsing doesn't compile the implementation

### 6. Unconditional Inline Implementation
Removed preprocessor guards and included implementation directly in header.

**Result**: Still undefined symbols at iOS link time

## Current State

All attempts to get K/N cinterop to compile C source files have failed. The iOS Shared framework is built without the C crypto functions, causing linker failures.

## Potential Solutions to Research

1. **Pre-compiled C Library**: Compile `where_crypto_impl.c` into a static library (.a) outside K/N, then link via cinterop's `linkerOpts`
2. **Objective-C Wrapper**: Wrap C functions in an Objective-C++ layer that K/N can more easily integrate
3. **K/N Native Module**: Check if there's a K/N mechanism for native modules that can include compiled C code
4. **XCFramework Integration**: Pre-build as XCFramework and link at Xcode level
5. **Custom Build Script**: Add custom Gradle task to compile C code before K/N cinterop runs
6. **Check K/N Documentation**: Search for "native sources" or "C source compilation" in K/N 2.0+ docs

## Files Involved

- **Header**: `shared/src/nativeInterop/cinterop/where_crypto.h` (declarations only)
- **Implementation**: `shared/src/nativeInterop/cinterop/where_crypto_impl.c`
- **Cinterop Config**: `shared/src/nativeInterop/cinterop/WhereCrypto.def`
- **Gradle Config**: `shared/build.gradle.kts` (iOS target configuration)
- **Linked Frameworks**: Security.framework, CoreFoundation.framework, CommonCrypto

## Notes

- The fix for Swift 6 strict concurrency (`@MainActor` annotations) already works
- The Security framework constant forward declarations are correct
- The issue is purely that the C source code isn't being compiled into the framework binary
