package org.koaks.framework.model

import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** Core-assigned stable association id. Valid across providers and turns. */
@JvmInline
value class ItemRef(val value: String) {
    init {
        require(value.isNotBlank()) { "ItemRef must not be blank" }
    }

    companion object {
        fun generate(prefix: String = "item"): ItemRef = ItemRef("${prefix}_${randomToken()}")
    }
}

@Serializable
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank" }
    }

    companion object {
        val OpenAI = ProviderId("openai")
        val OpenAIResponses = ProviderId("openai-responses")
        val Anthropic = ProviderId("anthropic")
        val Ollama = ProviderId("ollama")
        val Qwen = ProviderId("qwen")
        val ChatCompletions = ProviderId("chat-completions")
    }
}

/** Provider-native identifier. Providers ignore ids that do not belong to their namespace. */
@Serializable
data class ProviderScopedId(
    val providerId: ProviderId,
    val raw: String,
)

/** Returns the native id only when it belongs to [providerId]. */
fun ProviderScopedId?.rawFor(providerId: ProviderId): String? =
    this?.takeIf { it.providerId == providerId }?.raw

fun newIdempotencyKey(): String = "koaks_${randomToken()}"

internal fun randomToken(): String {
    val n = Random.nextLong().toULong().toString(16)
    val m = Random.nextLong().toULong().toString(16)
    return n + m
}
