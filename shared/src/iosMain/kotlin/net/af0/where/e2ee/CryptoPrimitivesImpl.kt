@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package net.af0.where.e2ee

import kotlinx.cinterop.*
import net.af0.where.e2ee.native.*

// ---------------------------------------------------------------------------
// SHA-256
// ---------------------------------------------------------------------------

internal actual fun sha256(data: ByteArray): ByteArray {
    val out = ByteArray(32)
    data.usePinned { d ->
        out.usePinned { o ->
            where_sha256(
                d.addressOf(0).reinterpret(),
                data.size.toULong(),
                o.addressOf(0).reinterpret(),
            )
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// HMAC-SHA-256
// ---------------------------------------------------------------------------

internal actual fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val out = ByteArray(32)
    key.usePinned { k ->
        data.usePinned { d ->
            out.usePinned { o ->
                where_hmac_sha256(
                    k.addressOf(0).reinterpret(),
                    key.size.toULong(),
                    d.addressOf(0).reinterpret(),
                    data.size.toULong(),
                    o.addressOf(0).reinterpret(),
                )
            }
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// X25519
// ---------------------------------------------------------------------------

actual fun generateX25519KeyPair(): RawKeyPair {
    val priv = ByteArray(32)
    val pub = ByteArray(32)
    val rc =
        priv.usePinned { p ->
            pub.usePinned { q ->
                where_x25519_keypair(p.addressOf(0).reinterpret(), q.addressOf(0).reinterpret())
            }
        }
    if (rc != 0) throw IllegalStateException("X25519 key generation failed")
    return RawKeyPair(priv, pub)
}

internal actual fun x25519(
    myPriv: ByteArray,
    theirPub: ByteArray,
): ByteArray {
    val out = ByteArray(32)
    val rc =
        out.usePinned { o ->
            myPriv.usePinned { p ->
                theirPub.usePinned { q ->
                    where_x25519_dh(
                        o.addressOf(0).reinterpret(),
                        p.addressOf(0).reinterpret(),
                        q.addressOf(0).reinterpret(),
                    )
                }
            }
        }
    if (rc != 0) throw IllegalStateException("X25519 DH failed")
    return out
}

// ---------------------------------------------------------------------------
// Ed25519
// ---------------------------------------------------------------------------

actual fun generateEd25519KeyPair(): RawKeyPair {
    val priv = ByteArray(32)
    val pub = ByteArray(32)
    val rc =
        priv.usePinned { p ->
            pub.usePinned { q ->
                where_ed25519_keypair(p.addressOf(0).reinterpret(), q.addressOf(0).reinterpret())
            }
        }
    if (rc != 0) throw IllegalStateException("Ed25519 key generation failed")
    return RawKeyPair(priv, pub)
}

internal actual fun ed25519Sign(
    priv: ByteArray,
    message: ByteArray,
): ByteArray {
    val sig = ByteArray(64)
    val rc =
        sig.usePinned { s ->
            priv.usePinned { p ->
                message.usePinned { m ->
                    where_ed25519_sign(
                        s.addressOf(0).reinterpret(),
                        p.addressOf(0).reinterpret(),
                        m.addressOf(0).reinterpret(),
                        message.size.toULong(),
                    )
                }
            }
        }
    if (rc != 0) throw IllegalStateException("Ed25519 sign failed")
    return sig
}

internal actual fun ed25519Verify(
    pub: ByteArray,
    message: ByteArray,
    sig: ByteArray,
): Boolean {
    val rc =
        pub.usePinned { p ->
            message.usePinned { m ->
                sig.usePinned { s ->
                    where_ed25519_verify(
                        p.addressOf(0).reinterpret(),
                        m.addressOf(0).reinterpret(),
                        message.size.toULong(),
                        s.addressOf(0).reinterpret(),
                    )
                }
            }
        }
    return rc == 0
}

// ---------------------------------------------------------------------------
// AES-256-GCM
// ---------------------------------------------------------------------------

internal actual fun aesgcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray {
    val out = ByteArray(plaintext.size + 16)
    val rc =
        key.usePinned { k ->
            nonce.usePinned { n ->
                aad.usePinned { a ->
                    plaintext.usePinned { p ->
                        out.usePinned { o ->
                            where_aesgcm_encrypt(
                                k.addressOf(0).reinterpret(),
                                n.addressOf(0).reinterpret(),
                                a.addressOf(0).reinterpret(),
                                aad.size.toULong(),
                                p.addressOf(0).reinterpret(),
                                plaintext.size.toULong(),
                                o.addressOf(0).reinterpret(),
                            )
                        }
                    }
                }
            }
        }
    if (rc != 0) throw IllegalStateException("AES-GCM encrypt failed")
    return out
}

internal actual fun aesgcmDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray {
    require(ciphertext.size >= 16) { "ciphertext too short to contain GCM tag" }
    val out = ByteArray(ciphertext.size - 16)
    val rc =
        key.usePinned { k ->
            nonce.usePinned { n ->
                aad.usePinned { a ->
                    ciphertext.usePinned { c ->
                        out.usePinned { o ->
                            where_aesgcm_decrypt(
                                k.addressOf(0).reinterpret(),
                                n.addressOf(0).reinterpret(),
                                a.addressOf(0).reinterpret(),
                                aad.size.toULong(),
                                c.addressOf(0).reinterpret(),
                                ciphertext.size.toULong(),
                                o.addressOf(0).reinterpret(),
                            )
                        }
                    }
                }
            }
        }
    if (rc != 0) throw IllegalArgumentException("AES-GCM authentication failed")
    return out
}
