package org.koaks.framework.model

/**
 * How a [ModelItem.ProviderItem] must be treated when memory trims or a fallback
 * provider cannot interpret it.
 *
 * - [Required]: dropping it is a prepare-time error (e.g. Anthropic thinking signature).
 * - [Preferred]: keep when possible; a fallback may drop it with an explicit warning.
 * - [Optional]: display-only; safe to drop.
 */
enum class ReplayPolicy {
    Required,
    Preferred,
    Optional,
}

/** Throws if this item cannot be dropped or translated onto [target]. */
fun ModelItem.ProviderItem.ensureDroppable(target: String) {
    if (replay != ReplayPolicy.Required) return
    throw AgentFrameworkException(
        AgentError.PreparationError(
            component = "provider",
            message = "cannot translate ReplayPolicy.Required item '$kind' onto $target",
        ),
    )
}
