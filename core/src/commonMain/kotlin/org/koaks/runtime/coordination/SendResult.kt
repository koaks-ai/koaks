package org.koaks.runtime.coordination

/** Result of accepting a message into a run inbox. */
sealed interface SendResult {
    data class Accepted(val messageId: Long) : SendResult
    data class Rejected(val reason: String) : SendResult
}
