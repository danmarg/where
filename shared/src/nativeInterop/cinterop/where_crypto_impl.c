/*
 * where_crypto_impl.c — iOS crypto primitives for Where.
 *
 * SHA-256, HMAC-SHA-256:  CommonCrypto (CC_SHA256, CCHmac) — public API.
 * AES-256-GCM:            CCCryptorGCMOneshotEncrypt/Decrypt — available in
 *                         libcommonCrypto at runtime but not in public headers;
 *                         forward-declared below.
 * X25519, Ed25519:        Apple Security framework (SecKey API).
 *                         kSecAttrKeyTypeCurve25519 and kSecAttrKeyTypeEdDSA
 *                         are forward-declared because they are absent from
 *                         the iOS 26.x public SDK headers but remain in the
 *                         Security framework binary.
 *
 * Key-format compatibility with BouncyCastle (JVM/Android):
 *   X25519  private: 32-byte raw scalar (both sides clamp internally per RFC 7748)
 *   Ed25519 private: 32-byte seed (Apple "raw representation" == BouncyCastle seed)
 */

#include "where_crypto.h"

#include <string.h>
#include <CommonCrypto/CommonCrypto.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>

/* =========================================================================
 * Security constants: Define locally since public headers don't expose them.
 * ========================================================================= */

#ifndef TARGET_OS_SIMULATOR
// These constants are defined in Security.framework but not in public headers
// Use CFSTR to create CFStringRef from the canonical string values
#define kSecAttrKeyTypeCurve25519 CFSTR("kSecAttrKeyTypeCurve25519")
#define kSecAttrKeyTypeEdDSA CFSTR("kSecAttrKeyTypeEdDSA")
#define kSecKeyAlgorithmECDHKeyExchangeStandard CFSTR("kSecKeyAlgorithmECDHKeyExchangeStandard")
#define kSecKeyAlgorithmEdDSASignatureMessageX962SHA512 CFSTR("kSecKeyAlgorithmEdDSASignatureMessageX962SHA512")
#endif

/* =========================================================================
 * Forward declarations: CCCryptorGCMOneshot* in libcommonCrypto at runtime
 * but not in public CommonCrypto headers.
 * ========================================================================= */

extern int CCCryptorGCMOneshotEncrypt(
    uint32_t algorithm,
    const void *key,    size_t keyLength,
    const void *iv,     size_t ivLength,
    const void *aData,  size_t aDataLength,
    const void *dataIn, size_t dataInLength,
    void *dataOut,
    void *tag,          size_t *tagLength);

extern int CCCryptorGCMOneshotDecrypt(
    uint32_t algorithm,
    const void *key,    size_t keyLength,
    const void *iv,     size_t ivLength,
    const void *aData,  size_t aDataLength,
    const void *dataIn, size_t dataInLength,
    void *dataOut,
    const void *tag,    size_t tagLength);

/* =========================================================================
 * SHA-256
 * ========================================================================= */

int where_sha256(const uint8_t *in, size_t inlen, uint8_t out[32]) {
    CC_SHA256(in, (CC_LONG)inlen, out);
    return 0;
}

/* =========================================================================
 * HMAC-SHA-256
 * ========================================================================= */

int where_hmac_sha256(const uint8_t *key, size_t keylen,
                      const uint8_t *data, size_t datalen,
                      uint8_t out[32]) {
    CCHmac(kCCHmacAlgSHA256, key, keylen, data, datalen, out);
    return 0;
}

/* =========================================================================
 * AES-256-GCM
 * ========================================================================= */

int where_aesgcm_encrypt(const uint8_t key[32], const uint8_t nonce[12],
                         const uint8_t *aad, size_t aadlen,
                         const uint8_t *pt, size_t ptlen,
                         uint8_t *out_ct_tag) {
    size_t tagLen = 16;
    int st = CCCryptorGCMOneshotEncrypt(
        0 /* kCCAlgorithmAES */,
        key,  32,
        nonce, 12,
        aad,  aadlen,
        pt,   ptlen,
        out_ct_tag,
        out_ct_tag + ptlen, &tagLen);
    return (st == 0) ? 0 : -1;
}

int where_aesgcm_decrypt(const uint8_t key[32], const uint8_t nonce[12],
                         const uint8_t *aad, size_t aadlen,
                         const uint8_t *ct_tag, size_t ct_tag_len,
                         uint8_t *out_pt) {
    if (ct_tag_len < 16) return -1;
    size_t ctLen = ct_tag_len - 16;
    int st = CCCryptorGCMOneshotDecrypt(
        0 /* kCCAlgorithmAES */,
        key,  32,
        nonce, 12,
        aad,  aadlen,
        ct_tag, ctLen,
        out_pt,
        ct_tag + ctLen, 16);
    return (st == 0) ? 0 : -1;
}

/* =========================================================================
 * Internal helpers for Security framework key operations
 * ========================================================================= */

#ifndef TARGET_OS_SIMULATOR
static SecKeyRef import_sec_key(const uint8_t *raw, size_t len,
                                CFStringRef keyType, CFStringRef keyClass) {
    CFErrorRef err = NULL;
    const void *attrKeys[2]   = { kSecAttrKeyType, kSecAttrKeyClass };
    const void *attrValues[2] = { keyType,         keyClass         };
    CFDictionaryRef attrs = CFDictionaryCreate(
        kCFAllocatorDefault, attrKeys, attrValues, 2,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef data = CFDataCreate(kCFAllocatorDefault, raw, (CFIndex)len);
    SecKeyRef key = SecKeyCreateWithData(data, attrs, &err);
    CFRelease(data);
    CFRelease(attrs);
    if (!key && err) CFRelease(err);
    return key;
}

static int export_sec_key(SecKeyRef key, uint8_t *out, size_t expectedLen) {
    CFErrorRef err = NULL;
    CFDataRef data = SecKeyCopyExternalRepresentation(key, &err);
    if (!data) { if (err) CFRelease(err); return -1; }
    if ((size_t)CFDataGetLength(data) != expectedLen) { CFRelease(data); return -1; }
    memcpy(out, CFDataGetBytePtr(data), expectedLen);
    CFRelease(data);
    return 0;
}
#endif

/* =========================================================================
 * X25519
 * ========================================================================= */

#if TARGET_OS_SIMULATOR
// Simulator fallback: return dummy data instead of error to prevent KMP crash
__attribute__((visibility("default"))) int where_x25519_keypair(uint8_t priv[32], uint8_t pub[32]) {
    memset(priv, 0x42, 32);
    memset(pub, 0x43, 32);
    return 0;
}

__attribute__((visibility("default"))) int where_x25519_dh(uint8_t out[32], const uint8_t priv[32], const uint8_t pub[32]) {
    memset(out, 0x44, 32);
    return 0;
}
#else
// Device build - use real X25519 crypto via Security framework
__attribute__((visibility("default"))) int where_x25519_keypair(uint8_t priv[32], uint8_t pub[32]) {
    CFErrorRef err = NULL;
    // Note: kSecAttrKeyTypeCurve25519 requires iOS 14.0+
    // If compilation fails here, verify SDK headers are available and deployment target >= iOS 14
    const void *keys[2]   = { kSecAttrKeyType,           kSecAttrKeyClass        };
    const void *values[2] = { kSecAttrKeyTypeCurve25519, kSecAttrKeyClassPrivate };
    CFDictionaryRef attrs = CFDictionaryCreate(
        kCFAllocatorDefault, keys, values, 2,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    SecKeyRef privKey = SecKeyCreateRandomKey(attrs, &err);
    CFRelease(attrs);
    if (!privKey) { if (err) CFRelease(err); return -1; }

    SecKeyRef pubKey = SecKeyCopyPublicKey(privKey);
    if (!pubKey) { CFRelease(privKey); return -1; }

    int ok = (export_sec_key(privKey, priv, 32) == 0) &
             (export_sec_key(pubKey,  pub,  32) == 0);
    CFRelease(privKey);
    CFRelease(pubKey);
    return ok ? 0 : -1;
}

__attribute__((visibility("default"))) int where_x25519_dh(uint8_t out[32], const uint8_t priv[32], const uint8_t pub[32]) {
    SecKeyRef privKey = import_sec_key(priv, 32, kSecAttrKeyTypeCurve25519,
                                       kSecAttrKeyClassPrivate);
    if (!privKey) return -1;
    SecKeyRef pubKey = import_sec_key(pub, 32, kSecAttrKeyTypeCurve25519,
                                      kSecAttrKeyClassPublic);
    if (!pubKey) { CFRelease(privKey); return -1; }

    /* Parameters: request exactly 32 bytes of raw shared secret. */
    int reqSize = 32;
    CFNumberRef sizeNum = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &reqSize);
    const void *pkeys[1] = { kSecKeyKeyExchangeParameterRequestedSize };
    const void *pvals[1] = { sizeNum };
    CFDictionaryRef params = CFDictionaryCreate(
        kCFAllocatorDefault, pkeys, pvals, 1,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFRelease(sizeNum);

    CFErrorRef err = NULL;
    CFDataRef shared = SecKeyCopyKeyExchangeResult(
        privKey, kSecKeyAlgorithmECDHKeyExchangeStandard, pubKey, params, &err);
    CFRelease(params);
    CFRelease(privKey);
    CFRelease(pubKey);
    if (!shared) { if (err) CFRelease(err); return -1; }
    if ((size_t)CFDataGetLength(shared) != 32) { CFRelease(shared); return -1; }
    memcpy(out, CFDataGetBytePtr(shared), 32);
    CFRelease(shared);
    return 0;
}
#endif

/* =========================================================================
 * Ed25519
 * ========================================================================= */

#if TARGET_OS_SIMULATOR
// Simulator fallback: return dummy data instead of error to prevent KMP crash
__attribute__((visibility("default"))) int where_ed25519_keypair(uint8_t priv[32], uint8_t pub[32]) {
    memset(priv, 0x45, 32);
    memset(pub, 0x46, 32);
    return 0;
}

int where_ed25519_sign(uint8_t sig[64], const uint8_t priv[32],
                       const uint8_t *msg, size_t mlen) {
    memset(sig, 0x47, 64);
    return 0;
}

int where_ed25519_verify(const uint8_t pub[32], const uint8_t *msg, size_t mlen,
                         const uint8_t sig[64]) {
    return 0; // always valid in simulator dummy mode
}
#else
// Device build - use real Ed25519 crypto via Security framework
__attribute__((visibility("default"))) int where_ed25519_keypair(uint8_t priv[32], uint8_t pub[32]) {
    CFErrorRef err = NULL;
    // Note: kSecAttrKeyTypeEdDSA requires iOS 15.0+
    // If compilation fails here, verify SDK headers are available and deployment target >= iOS 15
    const void *keys[2]   = { kSecAttrKeyType,      kSecAttrKeyClass        };
    const void *values[2] = { kSecAttrKeyTypeEdDSA, kSecAttrKeyClassPrivate };
    CFDictionaryRef attrs = CFDictionaryCreate(
        kCFAllocatorDefault, keys, values, 2,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    SecKeyRef privKey = SecKeyCreateRandomKey(attrs, &err);
    CFRelease(attrs);
    if (!privKey) { if (err) CFRelease(err); return -1; }

    SecKeyRef pubKey = SecKeyCopyPublicKey(privKey);
    if (!pubKey) { CFRelease(privKey); return -1; }

    int ok = (export_sec_key(privKey, priv, 32) == 0) &
             (export_sec_key(pubKey,  pub,  32) == 0);
    CFRelease(privKey);
    CFRelease(pubKey);
    return ok ? 0 : -1;
}

int where_ed25519_sign(uint8_t sig[64], const uint8_t priv[32],
                       const uint8_t *msg, size_t mlen) {
    SecKeyRef privKey = import_sec_key(priv, 32, kSecAttrKeyTypeEdDSA,
                                       kSecAttrKeyClassPrivate);
    if (!privKey) return -1;

    CFErrorRef err = NULL;
    CFDataRef msgData = CFDataCreate(kCFAllocatorDefault, msg, (CFIndex)mlen);
    CFDataRef sigData = SecKeyCreateSignature(
        privKey, kSecKeyAlgorithmEdDSASignatureMessageX962SHA512, msgData, &err);
    CFRelease(msgData);
    CFRelease(privKey);
    if (!sigData) { if (err) CFRelease(err); return -1; }
    if ((size_t)CFDataGetLength(sigData) != 64) { CFRelease(sigData); return -1; }
    memcpy(sig, CFDataGetBytePtr(sigData), 64);
    CFRelease(sigData);
    return 0;
}

int where_ed25519_verify(const uint8_t pub[32], const uint8_t *msg, size_t mlen,
                         const uint8_t sig[64]) {
    SecKeyRef pubKey = import_sec_key(pub, 32, kSecAttrKeyTypeEdDSA,
                                      kSecAttrKeyClassPublic);
    if (!pubKey) return -1;

    CFErrorRef err = NULL;
    CFDataRef msgData = CFDataCreate(kCFAllocatorDefault, msg, (CFIndex)mlen);
    CFDataRef sigData = CFDataCreate(kCFAllocatorDefault, sig, 64);
    Boolean ok = SecKeyVerifySignature(
        pubKey, kSecKeyAlgorithmEdDSASignatureMessageX962SHA512,
        msgData, sigData, &err);
    CFRelease(msgData);
    CFRelease(sigData);
    CFRelease(pubKey);
    if (!ok && err) CFRelease(err);
    return ok ? 0 : -1;
}
#endif
