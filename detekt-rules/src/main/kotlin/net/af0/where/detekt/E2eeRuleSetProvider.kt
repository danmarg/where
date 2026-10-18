package net.af0.where.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Rules for the E2EE module. They work on syntax alone (no type resolution), so "secret" means a
 * name from [SecretNames] or a `.bytes` access (the raw bytes behind a typed key), not a type.
 */
class E2eeRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "where-e2ee"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                RawKeyBytes(config),
                SecretComparison(config),
                InsecureRandom(config),
                SecretInLog(config),
            ),
        )
}
