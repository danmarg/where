/*
 * where_crypto.h — Crypto primitives for Where iOS (KMP cinterop header)
 *
 * Provides the 9 primitives expected/actual'd in CryptoPrimitives.kt:
 *   sha256, hmacSha256, aesgcmEncrypt, aesgcmDecrypt,
 *   x25519KeyPair, x25519, ed25519KeyPair, ed25519Sign, ed25519Verify
 *
 * Error convention: 0 = success, -1 = failure.
 */
#ifndef WHERE_CRYPTO_H
#define WHERE_CRYPTO_H

#include <stdint.h>
#include <stddef.h>

/* SHA-256 */
int where_sha256(const uint8_t *in, size_t inlen, uint8_t out[32]);

/* HMAC-SHA-256 */
int where_hmac_sha256(const uint8_t *key, size_t keylen,
                      const uint8_t *data, size_t datalen,
                      uint8_t out[32]);

/*
 * AES-256-GCM encrypt.
 * out_ct_tag must have room for ptlen + 16 bytes (ciphertext + 16-byte tag).
 */
int where_aesgcm_encrypt(const uint8_t key[32], const uint8_t nonce[12],
                         const uint8_t *aad, size_t aadlen,
                         const uint8_t *pt, size_t ptlen,
                         uint8_t *out_ct_tag);

/*
 * AES-256-GCM decrypt + verify.
 * ct_tag_len = ciphertext length + 16 (last 16 bytes are the tag).
 * out_pt must have room for ct_tag_len - 16 bytes.
 * Returns -1 on authentication failure.
 */
int where_aesgcm_decrypt(const uint8_t key[32], const uint8_t nonce[12],
                         const uint8_t *aad, size_t aadlen,
                         const uint8_t *ct_tag, size_t ct_tag_len,
                         uint8_t *out_pt);

/* X25519 key generation — generates random 32-byte private scalar and derives public key. */
int where_x25519_keypair(uint8_t priv[32], uint8_t pub[32]);

/* X25519 Diffie-Hellman: out = scalar(priv) * point(pub). */
int where_x25519_dh(uint8_t out[32], const uint8_t priv[32], const uint8_t pub[32]);

/* Ed25519 key generation — generates 32-byte seed (private) and 32-byte public key. */
int where_ed25519_keypair(uint8_t priv[32], uint8_t pub[32]);

/* Ed25519 sign — sig is 64 bytes. */
int where_ed25519_sign(uint8_t sig[64], const uint8_t priv[32],
                       const uint8_t *msg, size_t mlen);

/* Ed25519 verify — returns 0 if valid, -1 if invalid. */
int where_ed25519_verify(const uint8_t pub[32], const uint8_t *msg, size_t mlen,
                         const uint8_t sig[64]);

#endif /* WHERE_CRYPTO_H */
