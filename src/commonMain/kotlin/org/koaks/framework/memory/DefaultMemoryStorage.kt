package org.koaks.framework.memory

import org.koaks.framework.entity.Message

object DefaultMemoryStorage : IMemoryStorage {

    private val messageContainer: HashMap<String, MutableList<Message>> = HashMap()

    override fun getMessageList(memoryId: String): MutableList<Message> {
        return messageContainer[memoryId] ?: run {
            val newList = mutableListOf<Message>()
            messageContainer[memoryId] = newList
            newList
        }
    }

    override fun addMessage(message: Message, memoryId: String) {
        getMessageList(memoryId).addLast(message)
    }

    override fun deleteMessageList(memoryId: String) {
        messageContainer.remove(memoryId)
    }

}