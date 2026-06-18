# Plan: Add Anthropic (Claude) Messages API provider to koaks

## Context

koaks supports OpenAI / Qwen / Ollama via a clean, self-contained provider abstraction (koaks-model:<name>, 5 files each). We want first-class Anthropic (Claude) support against the Messages API (POST /v1/messages). The provider layer already fits this with almost no core changes — the one real friction is auth: KtorTransport hardcodes Authorization: Bearer <key>, but Anthropic needs x-api-key: <key> + anthropic-version: 2023-06-01.

The user chose the clean auth approach: introduce a small auth abstraction in core (instead of the apiKey=null + customHeaders workaround), defaulting to Bearer so existing providers are untouched.

Build with JDK 21 (not the machine-default JDK 25) — see memory.

---

# Part A — Core: pluggable auth scheme

## New file

`core/src/commonMain/kotlin/org/koaks/framework/provider/AuthScheme.kt`

```kotlin
package org.koaks.framework.provider

/** How a provider authenticates the request. Default is [Bearer]. */
sealed interface AuthScheme {
    /** Auth headers for [apiKey] (empty when no key is set). */
    fun headers(apiKey: String?): List<Pair<String, String>>

    /** `Authorization: Bearer <key>` — OpenAI / Qwen. The default. */
    data object Bearer : AuthScheme {
        override fun headers(apiKey: String?) =
            if (apiKey.isNullOrBlank()) emptyList()
            else listOf("Authorization" to "Bearer $apiKey")
    }

    /** A header carrying the key verbatim, e.g. `x-api-key` (Anthropic). */
    data class Header(val name: String) : AuthScheme {
        override fun headers(apiKey: String?) =
            if (apiKey.isNullOrBlank()) emptyList() else listOf(name to apiKey)
    }
}
```

### Edit

`core/.../provider/ModelConfig.kt`

Add one field (default keeps every existing provider Bearer):

```kotlin
val auth: AuthScheme = AuthScheme.Bearer,
```

### Edit

`core/.../transport/KtorTransport.kt` (openStream, lines 106-109)

Replace the hardcoded Bearer block:

```kotlin
for ((k, v) in config.auth.headers(config.apiKey)) header(k, v)
for ((k, v) in config.customHeaders) header(k, v)
```

Remove the now-unused import `io.ktor.http.HttpHeaders` (line 14) if nothing else references it.

This is fully backward-compatible: openai/qwen/ollama keep auth = Bearer by default and behave identically.

---

# Part B — New module koaks-model:anthropic

Mirror `koaks-model/openai/` exactly.

Package:

```text
org.koaks.provider.anthropic
```

## build.gradle.kts

Copy openai's verbatim, swap `koaks-model-openai` → `koaks-model-anthropic` (plugins `koaks.kmp.library` + `koaks.kmp.publishing`, single dep `implementation(project(":core"))`).

### Five source files under

```text
koaks-model/anthropic/src/commonMain/kotlin/org/koaks/provider/anthropic/
```

## 1. AnthropicChatRequest.kt

`@Serializable` request body + polymorphic content blocks.

Key Anthropic-specific shapes vs OpenAI:

- `max_tokens` is required.
- `system` is a top-level string param (not a role:"system" message).
- `messages` only carry user / assistant; content is a block array.
- `tools` use `input_schema` (not parameters); `tool_use.input` is a JSON object (not a stringified one like OpenAI).

```kotlin
@Serializable
data class AnthropicChatRequest(
    @SerialName("model") val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("messages") val messages: List<AnthropicMessage>,
    @SerialName("system") val system: String? = null,
    @SerialName("tools") val tools: List<AnthropicTool>? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    @SerialName("thinking") val thinking: JsonObject? = null, // optional opt-in
)

@Serializable
data class AnthropicMessage(val role: String, val content: List<AnthropicContentBlock>)

@Serializable
sealed interface AnthropicContentBlock {
    @Serializable @SerialName("text")
    data class Text(val text: String) : AnthropicContentBlock

    @Serializable @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement
    ) : AnthropicContentBlock

    @Serializable @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : AnthropicContentBlock
}

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)
```

The default `JsonUtil.json` already uses class discriminator `"type"` (matching Anthropic) and `explicitNulls=false` (omits nulls) — no custom Json needed.

---

## 2. AnthropicChatResponse.kt

Single stream-event class with all-optional fields + `ignoreUnknownKeys` (mirrors OpenAIChatResponse's single-class style).

Covers `message_start`, `content_block_start`, `content_block_delta`, `message_delta`, `error`:

```kotlin
@Serializable
data class AnthropicChatResponse(
    val type: String? = null,
    val index: Int? = null,
    val message: Message? = null,                                  // message_start
    @SerialName("content_block") val contentBlock: ContentBlock? = null, // content_block_start
    val delta: Delta? = null,                                      // content_block_delta / message_delta
    val usage: Usage? = null,                                      // message_delta top-level
    val error: ErrorOutput? = null,
) {
    @Serializable
    data class Message(val usage: Usage? = null)

    @Serializable
    data class ContentBlock(
        val type: String? = null,
        val id: String? = null,
        val name: String? = null
    )

    @Serializable
    data class Delta(
        val type: String? = null,
        val text: String? = null,
        val thinking: String? = null,
        @SerialName("partial_json") val partialJson: String? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )

    @Serializable
    data class ErrorOutput(
        val type: String? = null,
        val message: String? = null
    )
}
```

---

## 3. AnthropicWireDecoder.kt

Stateful decoder mirroring OpenAIWireDecoder (accumulate per index, emit ToolCallCompleted at finish).

Dispatch on `chunk.type`:

- `error` → `ModelEvent.Failed(AgentError.ModelError(msg, retriable=false))`
- `message_start` → capture `message.usage.inputTokens` → prompt tokens
- `content_block_start` with `content_block.type=="tool_use"` → seed
  `toolCalls[index] = ToolAcc(id, name)`
- `content_block_delta` → by `delta.type`:
    - `text_delta` → `TextDelta(delta.text)`
    - `thinking_delta` → `ReasoningDelta(delta.thinking)`
    - `input_json_delta` → append `partial_json` to `toolCalls[index].args`; emit
      `ToolCallDelta(id, index, argumentsDelta = partialJson)`
- `message_delta` → capture `usage.outputTokens` → completion tokens
- `content_block_stop / message_stop / ping` → ignore
- `finish()` → emit `ToolCallCompleted` per accumulator sorted by index
  (`args .ifBlank { "{}" }`), then
  `Completed(Usage(prompt, completion, prompt+completion))`

---

## 4. AnthropicChatModel.kt

Extends `ChatModel<AnthropicChatRequest, AnthropicChatResponse>` (mirror OpenAIChatModel).

Holds `AnthropicParams` + `ModelCapabilities`.

### toWire mapping rules

- System hoist: concat all `Role.SYSTEM` messages' text → top-level system (`null` if none).
- Messages: map remaining messages to `AnthropicMessage`:
    - `Role.USER` text → user msg, `[Text]`
    - `Role.ASSISTANT` → assistant msg with `[Text?]` + `ToolUse(...)` per `ContentPart.ToolCallPart`
      (`input = JsonUtil.json.parseToJsonElement(call.arguments.ifBlank{"{}"})`)
    - `Role.TOOL` (`ToolResultPart`) → user msg with a `ToolResult` block
      (`tool_use_id = callId`, `content = output`, `is_error = isError`)
    - Coalesce consecutive `Role.TOOL` messages into a single user message with multiple tool_result blocks (Anthropic expects all results for a turn in one following user turn; consecutive user msgs are merged anyway, but coalescing is the correct, robust shape).
- tools: `req.tools` → `AnthropicTool(name, description, input_schema = schema.parameters)`; null when empty.
- `max_tokens = params.maxTokens`; `stream = req.stream`; pass through `temperature/topP/topK/stopSequences`; thinking only when opted in.
- `adapter = WireAdapter(AnthropicChatRequest.serializer(), AnthropicChatResponse.serializer())`,
  `newDecoder() = AnthropicWireDecoder()`.

### AnthropicParams

(provider-native, bound to the model):

```kotlin
maxTokens: Int = 4096,
temperature/topP: Double? = null,
topK: Int? = null,
stopSequences: List<String>? = null,
thinking: JsonObject? = null
```

---

## 5. AnthropicSelector.kt

Mirror OpenAISelector:

```kotlin
const val ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages"
const val ANTHROPIC_DEFAULT_VERSION = "2023-06-01"

fun ModelScope.anthropic(
    baseUrl: String = ANTHROPIC_DEFAULT_BASE_URL,
    apiKey: String,
    modelName: String,
    block: AnthropicConfig.() -> Unit = {},
): ModelSelection { ... return custom(AnthropicChatModel(cfg.toConfig(), transport, cfg.params(), cfg.capabilities())) }
```

`AnthropicConfig` (`@AgentDSL`) exposes:

- `maxTokens`
- `temperature`
- `topP`
- `topK`
- `stopSequences`
- an optional `anthropicVersion = ANTHROPIC_DEFAULT_VERSION`
- a `capabilities { }` sub-scope (mirror OpenAICapabilitiesScope; default `jsonMode = false` — Anthropic has no `response_format: json_object`).

### toConfig() returns

```kotlin
ModelConfig(
    baseUrl = baseUrl,
    apiKey = apiKey,
    modelName = modelName,
    auth = AuthScheme.Header("x-api-key"),
    customHeaders = mapOf("anthropic-version" to anthropicVersion),
)
```

---

# Part C — Wiring

- `settings.gradle.kts`: add `include("koaks-model:anthropic")` after the openai line.
- `tests/build.gradle.kts` (commonTest deps): add

```kotlin
implementation(project(":koaks-model:anthropic"))
```

---

# Part D — Tests

New test:

```text
tests/src/commonTest/kotlin/org/koaks/provider/anthropic/AnthropicWireDecoderTest.kt
```

Mirror OpenAIWireDecoderTest, feeding AnthropicChatResponse events:

- `assembles_tool_call_across_chunks`:
    - `message_start` (input usage)
    - → `content_block_start(tool_use id+name)`
    - → two `input_json_delta` fragments
    - → `message_delta(output usage)`
    - → assert single `ToolCallCompleted` with reassembled name+args and Completed totals.

- `forwards_text_and_thinking_as_distinct_events`:
    - `text_delta` → `TextDelta`,
    - `message_delta(output usage)` → assert single `ToolCallCompleted` with reassembled name+args and Completed totals.
    - `thinking_delta` → `ReasoningDelta`.

- `reports_error_chunk_as_failed`:
    - `type="error"` → `ModelEvent.Failed`.

- (optional) parallel tool calls across two indices emitted in index order.

---

# Part E — Verification

With JDK 21 active:

```bash
./gradlew :core:compileKotlinJvm :koaks-model:anthropic:compileKotlinJvm
./gradlew :tests:jvmTest --tests "org.koaks.provider.anthropic.*"
```

Then a full sanity build to confirm the core auth refactor didn't regress other

Then a full sanity build to confirm the core auth refactor didn't regress other providers:

```bash
./gradlew :tests:jvmTest --tests "org.koaks.provider.*" --tests "org.koaks.framework.transport.*"
```

Optional manual smoke test: add an `anthropic(apiKey, modelName="claude-opus-4-8")` block to `examples/QuickStart.kt` and run with a real key (requires `max_tokens`; `temperature/top_p/top_k` are rejected on Opus 4.7+ thinking-only providers:

```bash
./gradlew :tests:jvmTest --tests "org.koaks.provider.*" --tests "org.koaks.framework.transport.*"
```

Optional manual smoke test: add an `anthropic(apiKey, modelName="claude-opus-4-8")` block to `examples/QuickStart.kt` and run with a real key (requires `max_tokens`; `temperature/top_p/top_k` are rejected on Opus 4.7+ thinking-only models — leave them unset there, or use a Sonnet/Haiku model).

---

# Notes / deferred

- Non-streaming path is out of scope (same as other providers — the agent loop drives SSE streaming; stream is passed through from ChatRequest).
- `temperature/top_p/top_k` are exposed as native params but 400 on Opus 4.7/4.8/Fable — documented in AnthropicConfig KDoc; safe on Sonnet/Haiku.
- `thinking` left as an optional opt-in (`{"type":"adaptive"}`); the decoder already turns `thinking_delta` into `ReasoningDelta`.
- Vision/image content parts not mapped in this pass (text + tools only), matching the current OpenAI provider scope.