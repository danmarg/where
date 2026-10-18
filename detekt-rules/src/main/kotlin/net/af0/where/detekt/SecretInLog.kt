package net.af0.where.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression

/**
 * Secrets never go into strings, log calls or diagnostic events. Flags a string template entry
 * that mentions a secret, and a call to one of [logFunctions] with a secret in its arguments.
 * Secrets print as "redacted", but `.bytes` (and a hex of it) would not.
 */
class SecretInLog(config: Config) : Rule(config) {
    override val issue =
        Issue(
            javaClass.simpleName,
            Severity.Security,
            "Secret material in a string or log call.",
            Debt.FIVE_MINS,
        )

    private val secretNames: List<String> by config(DEFAULT_SECRET_NAMES)

    private val logFunctions: List<String> by config(
        listOf("addDiagnosticEvent", "println", "print", "debugLog", "log", "trace", "debug", "info", "warn", "error"),
    )

    private val names by lazy { secretNames.toSet() }

    override fun visitStringTemplateEntryWithExpression(entry: KtStringTemplateEntryWithExpression) {
        super.visitStringTemplateEntryWithExpression(entry)
        val expression = entry.expression ?: return
        if (expression.mentionsSecret(names)) {
            report(CodeSmell(issue, Entity.from(entry), "'${entry.text}' puts a secret in a string."))
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in logFunctions) return
        if (expression.valueArguments.any { it.getArgumentExpression()?.mentionsSecret(names) == true }) {
            report(CodeSmell(issue, Entity.from(expression), "'$callee' is passed a secret."))
        }
    }
}
