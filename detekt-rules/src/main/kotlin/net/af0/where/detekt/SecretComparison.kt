package net.af0.where.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Secrets are compared with constantTimeEquals, never `==`, `!=`, `equals` or `contentEquals`.
 * (SecretBytes.equals is constant-time too, but raw `.bytes` aren't, and the explicit call keeps
 * the intent visible.)
 */
class SecretComparison(config: Config) : Rule(config) {
    override val issue =
        Issue(
            javaClass.simpleName,
            Severity.Security,
            "Comparison of secret material; use constantTimeEquals.",
            Debt.FIVE_MINS,
        )

    private val secretNames: List<String> by config(DEFAULT_SECRET_NAMES)

    private val names by lazy { secretNames.toSet() }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        val op = expression.operationToken
        if (op != KtTokens.EQEQ && op != KtTokens.EXCLEQ) return
        if (listOfNotNull(expression.left, expression.right).any { it.isSecret(names) }) {
            report(CodeSmell(issue, Entity.from(expression), "'${expression.text}' compares a secret; use constantTimeEquals."))
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text
        if (callee != "equals" && callee != "contentEquals") return
        val receiver = (expression.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == expression }?.receiverExpression
        val operands = listOfNotNull<KtExpression>(receiver) + expression.valueArguments.mapNotNull { it.getArgumentExpression() }
        if (operands.any { it.isSecret(names) }) {
            report(CodeSmell(issue, Entity.from(expression), "'$callee' on a secret; use constantTimeEquals."))
        }
    }
}
