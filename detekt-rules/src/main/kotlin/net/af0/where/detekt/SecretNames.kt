package net.af0.where.detekt

import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Names that hold secret key material in the E2EE module: SessionState's secret fields, the
 * KDF outputs and the usual locals. Configurable per rule as `secretNames`.
 */
internal val DEFAULT_SECRET_NAMES =
    listOf(
        "rootKey", "newRootKey",
        "chainKey", "sendChainKey", "recvChainKey", "newChainKey",
        "headerKey", "sendHeaderKey", "nextHeaderKey", "prevSendHeaderKey", "newHeaderKey",
        "messageKey", "messageNonce", "mk",
        "localDhPriv", "aliceEkPriv", "priv", "newLocalPriv",
        "sk", "dhOut", "dhOutput", "kConfirm", "kName",
        "discoverySecret",
    )

/** The raw bytes behind a typed key (`KeyBytes.bytes`). */
internal const val RAW_BYTES = "bytes"

/** The name an expression ends in: `b` for `a.b`, `(a.b)` and `b`; null for anything else. */
internal fun KtExpression.terminalName(): String? =
    when (this) {
        is KtNameReferenceExpression -> getReferencedName()
        is KtQualifiedExpression -> (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        is KtParenthesizedExpression -> expression?.terminalName()
        else -> null
    }

/** True if the expression itself is a secret: it ends in a secret name or in `.bytes`. */
internal fun KtExpression.isSecret(secretNames: Set<String>): Boolean = terminalName().let { it == RAW_BYTES || it in secretNames }

/** Selectors that reveal only a secret's length, which is public. */
private val LENGTH_SELECTORS = setOf("size", "lastIndex", "indices")

/**
 * True if a secret name or a `.bytes` access appears anywhere inside the expression, other than
 * as the receiver of a length selector (`key.bytes.size`).
 */
internal fun KtExpression.mentionsSecret(secretNames: Set<String>): Boolean =
    (listOf(this) + collectDescendantsOfType<KtExpression>()).any { e ->
        e.isSecret(secretNames) && !e.isLengthReceiver()
    }

private fun KtExpression.isLengthReceiver(): Boolean {
    // `a.bytes.size` parses as ((a.bytes).size): both `a.bytes` and its selector `bytes` count.
    val qualified = parent as? KtQualifiedExpression ?: return false
    val receiver = if (qualified.selectorExpression == this) qualified else this
    val outer = receiver.parent as? KtQualifiedExpression ?: return false
    return outer.receiverExpression == receiver && outer.terminalName() in LENGTH_SELECTORS
}
