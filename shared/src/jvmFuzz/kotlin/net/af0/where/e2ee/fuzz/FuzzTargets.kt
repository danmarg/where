package net.af0.where.e2ee.fuzz

/** Every fuzz target by name; `-Pfuzz.target=<name>` runs `<name>Fuzzer`, seeded from corpus/<name>. */
internal val fuzzTargets: Map<String, (ByteArray) -> Unit> =
    mapOf(
        "DecryptHeader" to DecryptHeaderFuzzer::fuzzerTestOneInput,
        "DecodeMessage" to DecodeMessageFuzzer::fuzzerTestOneInput,
        "Unpad" to UnpadFuzzer::fuzzerTestOneInput,
        "QrPayload" to QrPayloadFuzzer::fuzzerTestOneInput,
        "KeyExchangeInit" to KeyExchangeInitFuzzer::fuzzerTestOneInput,
        "MailboxPayload" to MailboxPayloadFuzzer::fuzzerTestOneInput,
        "DecryptMessage" to DecryptMessageFuzzer::fuzzerTestOneInput,
    )
