package org.koaks.framework.memory

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koaks.framework.entity.Message

object NoneMemoryStorage : IMemoryStorage {

    private val logger = KotlinLogging.logger {}

    override fun getMessageList(memoryId: String): MutableList<Message> {
        logger.debug { "Used NoneMemoryStorage" }
        return mutableListOf()
    }

    override fun addMessage(message: Message, memoryId: String) {
        logger.debug { "Used NoneMemoryStorage" }
    }

    override fun deleteMessageList(memoryId: String) {
        logger.debug { "Used NoneMemoryStorage" }
    }

}