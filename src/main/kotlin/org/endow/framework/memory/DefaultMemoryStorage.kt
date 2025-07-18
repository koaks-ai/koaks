package org.endow.framework.memory

import org.endow.framework.entity.Message

object DefaultMemoryStorage : IMemoryStorage {

    private val messageContainer: HashMap<String, MutableList<Message>> = HashMap()

    override fun getMessageList(memoryId: String): MutableList<Message> {
        return messageContainer[memoryId] ?: run {
            val newList = mutableListOf<Message>()
            messageContainer[memoryId] = newList
            newList
        }
    }

    override fun addMessage(message: Message, msgId: String) {
        getMessageList(msgId).addLast(message)
    }

    override fun deleteMessageList(memoryId: String) {
        messageContainer.remove(memoryId)
    }

}