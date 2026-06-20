package examples

import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.use
import org.koaks.provider.openai.openai

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Multi-segment & dynamic system instructions.
 *
 * Instead of a single `instructions = "..."` string, the `instructions { }` block
 * composes the system prompt from ordered segments:
 *
 *  - `+"..."` / `text("...")` — static text fixed at build time.
 *  - `dynamic { ... }`        — a suspend provider resolved ONCE per run (when the
 *                               initial messages are built), so it can read run-time
 *                               context (clock, user profile, a retrieval call).
 *                               Returning `null`/blank omits that segment.
 *
 * All non-blank segments are joined with a blank line into the single system message
 * the loop prepends.
 *
 * Note: for KV-cache friendliness, keep the resolved instructions stable across the turns
 * of a conversation — changing them mid-conversation invalidates the provider's prompt cache.
 */
fun main() = runBlocking {
    // Pretend this comes from a session / database lookup at request time.
    val userId = "user-1001"

    val agent = agent {
        name = "personalized-assistant"
        instructions {
            +"你是一个简洁、乐于助人的助手。"
            +"始终用中文回答。"

            // Resolved per run — keeps the date fresh without rebuilding the agent.
            dynamic { "今天的日期是 ${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}。" }

            // Returns null when there's no profile, so the segment is simply skipped.
            dynamic { lookupUserProfile(userId)?.let { "用户偏好：$it" } }
        }
        model {
            openai(
                baseUrl = EnvTools.loadValue("BASE_URL"),
                apiKey = EnvTools.loadValue("API_KEY"),
                modelName = "qwen3.7-plus",
            ) {
                temperature = 0.7
            }
        }
    }

    agent.use {
        println(it.run("根据你掌握的信息，给我一句今天的问候。").text)
    }
}

/** Stand-in for a real profile store; returns null when nothing is on file. */
private fun lookupUserProfile(userId: String): String? =
    if (userId == "user-1001") "喜欢简短、要点式的回答" else null
