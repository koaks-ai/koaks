package org.koaks.framework.memory

import org.koaks.framework.model.ModelItem
import org.koaks.framework.model.Usage

fun completedTurn(vararg items: ModelItem, usage: Usage = Usage.ZERO, id: String = "t"): ConversationTurn =
    ConversationTurn(
        id = id,
        status = TurnStatus.Completed,
        items = items.toList(),
        usage = usage,
    )

suspend fun ThreadMemory.stored(): List<ModelItem> = load(emptyList()).transcript

