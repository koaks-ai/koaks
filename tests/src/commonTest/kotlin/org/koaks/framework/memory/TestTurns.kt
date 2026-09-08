package org.koaks.framework.memory

import org.koaks.framework.model.ModelItem

suspend fun ThreadMemory.stored(): List<ModelItem> = load(emptyList()).transcript

suspend fun ThreadMemory.appendAll(vararg items: ModelItem) {
    items.forEach { append(it) }
}
