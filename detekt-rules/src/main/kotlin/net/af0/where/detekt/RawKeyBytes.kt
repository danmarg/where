package net.af0.where.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.isPrivate

/**
 * Keys, tokens and fingerprints use the typed wrappers in Keys.kt, not ByteArray. Flags a
 * non-private property, parameter or function whose type is or contains ByteArray (`ByteArray?`,
 * `List<ByteArray>`, `Map<String, ByteArray>`) and whose name says it holds key material (see
 * [namePattern], plurals included; `sk` alone or as a camel-case suffix, so not `task`). The primitive layer and wire codecs are excluded by path in
 * the detekt config; anything else that must stay raw (a public wire field) is suppressed in place
 * with a reason.
 */
class RawKeyBytes(config: Config) : Rule(config) {
    override val issue =
        Issue(
            javaClass.simpleName,
            Severity.Security,
            "Key material as a raw ByteArray; use the typed wrapper from Keys.kt.",
            Debt.TWENTY_MINS,
        )

    private val namePattern: String by config("(?i:.*(key|secret|token|priv|pub|fp|fingerprint|nonce|seed|ikm)s?)|sk|.*[a-z0-9]Sk")

    private val regex by lazy { Regex(namePattern) }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (!property.isLocal) check(property, property.typeReference)
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        // Function and constructor parameters; lambda and catch parameters have no owner declaration.
        val owner = parameter.ownerFunction ?: return
        if (owner is KtNamedFunction && owner.isLocal) return
        check(parameter, parameter.typeReference, owner.isPrivate() || owner.containingClassOrObject?.isPrivate() == true)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isLocal) check(function, function.typeReference)
    }

    private fun check(
        declaration: KtCallableDeclaration,
        type: KtTypeReference?,
        ownerPrivate: Boolean = false,
    ) {
        if (ownerPrivate || declaration.isPrivate() || declaration.containingClassOrObject?.isPrivate() == true) return
        val typeText = type?.text ?: return
        val name = declaration.name ?: return
        if (BYTE_ARRAY.containsMatchIn(typeText) && regex.matches(name)) {
            report(CodeSmell(issue, Entity.from(declaration), "'$name' holds key material as ByteArray; use a typed wrapper."))
        }
    }

    private companion object {
        val BYTE_ARRAY = Regex("""\bByteArray\b""")
    }
}
