package org.koaks.framework.model

import kotlinx.serialization.Serializable

/**
 * The role of a [Message] in a conversation.
 */
@Serializable
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
