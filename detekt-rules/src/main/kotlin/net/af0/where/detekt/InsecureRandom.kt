package net.af0.where.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * All randomness comes from the platform CSPRNG via randomBytes(). kotlin.random.Random and
 * java.util.Random are predictable.
 */
class InsecureRandom(config: Config) : Rule(config) {
    override val issue =
        Issue(
            javaClass.simpleName,
            Severity.Security,
            "Non-cryptographic random number generator; use randomBytes().",
            Debt.FIVE_MINS,
        )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val fqName = importDirective.importedFqName?.asString() ?: return
        if (fqName.startsWith("kotlin.random") || fqName == "java.util.Random") {
            report(CodeSmell(issue, Entity.from(importDirective), "Import of $fqName; use randomBytes()."))
        }
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (expression.getStrictParentOfType<KtImportDirective>() != null) return // reported above
        if (expression is KtNameReferenceExpression && expression.getReferencedName() == "Random") {
            report(CodeSmell(issue, Entity.from(expression), "Use of Random; use randomBytes()."))
        }
    }
}
