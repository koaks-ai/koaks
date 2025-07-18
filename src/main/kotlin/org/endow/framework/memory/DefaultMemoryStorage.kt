package org.endow.framework.memory

import org.endow.framework.entity.Message

object DefaultMemoryStorage : IMemoryStorage {

    private val messageContainer: HashMap<String, MutableList<Message>> = HashMap()

    override fun getMessageList(msgId: String): MutableList<Message> {
        return messageContainer[msgId] ?: run {
            val newList = mutableListOf<Message>()
            messageContainer[msgId] = newList
            newList
        }
    }

    override fun addMessage(message: Message, msgId: String) {
        getMessageList(msgId).addLast(message)
    }

    override fun deleteMessageList(msgId: String) {
        messageContainer.remove(msgId)
    }

}