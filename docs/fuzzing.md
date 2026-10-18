# Fuzzing

[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz targets for every parser of
attacker-controlled bytes, in `shared/src/jvmFuzz`. They live in a JVM compilation of their own
(`fuzz`), associated with `main`, so they can call the e2ee internals while Jazzer stays off the
test classpath.

| Target | Under test | What it builds from the input |
|---|---|---|
| `DecryptHeader` | `Session.decryptHeader` | a raw envelope; or a chosen plaintext sealed under the right key, so the parsing after authentication runs too; or one sealed under the wrong key |
| `DecodeMessage` | `Session.decodeMessage` | the plaintext JSON |
| `Unpad` | `Session.unpad` | a padded plaintext, checked against a reference implementation |
| `QrPayload` | `QrPayload.fromUrl`, then `KeyExchange.bobProcessQr` | an invite link, raw or wrapped as its Base64 fragment |
| `KeyExchangeInit` | mailbox decoding, then `KeyExchange.aliceProcessInit` | raw JSON; or a genuine init from a chosen scanner key and name, then one field corrupted |
| `MailboxPayload` | `decodeMailboxPayload` | a payload as the server returns it |
| `DecryptMessage` | `Session.decryptMessage` against a fixed, valid receive state | raw bytes; a genuine frame with bytes flipped; an authenticated frame with an arbitrary plaintext; a genuine frame with a chosen pre-decrypted header |

Every target checks the same properties. A rejected input fails only with a typed
`WhereException`; anything else (`IllegalArgumentException`, `IndexOutOfBounds`,
`SerializationException`, an `Error`) is a finding. State a call must not change stays
unchanged: `decryptMessage`'s input session, and the invite's private key. Where the input
round-trips (decoded messages, mailbox payloads, invite links), it does. The `DecryptMessage`
fixture's frames each decrypt by a different path: cached skipped key, in order, after a gap, and
both new-epoch frames.

## Running

```bash
./gradlew :shared:fuzzCorpusTest                                # committed corpus + seeded random inputs, every target (runs in CI)
./gradlew :shared:fuzz -Pfuzz.target=DecryptMessage -Pfuzz.seconds=600   # Jazzer, one target
./gradlew :shared:generateFuzzSeeds                             # rewrite well-formed seeds into the corpus
```

`fuzz` grows `shared/build/fuzz/<target>/corpus` from the committed seeds in
`shared/src/jvmFuzz/corpus/<target>`. A finding fails the task and leaves the input, plus a Java
reproducer, in `shared/build/fuzz/<target>/`. On Linux, libsodium must be installed (as for
`jvmTest`).

`.github/workflows/fuzz.yml` runs every target for 10 minutes nightly (`workflow_dispatch` takes
other durations). A finding is uploaded as an artifact.

## When it finds something

Fix it, then commit the crashing input to `shared/src/jvmFuzz/corpus/<target>/` so
`fuzzCorpusTest` keeps it fixed. A unit test of the specific case is still worth adding next to
the code (see `MessagePlaintextCodecTest.locWithNonFiniteValueDegradesToKeepalive`, the first
finding). If the property itself was wrong, fix the target and keep the input anyway.
