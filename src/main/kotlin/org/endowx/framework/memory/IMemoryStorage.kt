package org.endow.framework.memory

import org.endow.framework.entity.Message

interface IMemoryStorage {

    /**
     * get message list by message id，it contains all the message records within the length range in this conversation.
     * @param memoryId Message ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     * @return MutableList<Message>
     */
    fun getMessageList(memoryId: String): MutableList<Message>

    /**
     * add message to message list by message id.
     * @param memoryId Message ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     * @param message Message
     */
    fun addMessage(message: Message, memoryId: String)

    /**
     * delete message list by message id.
     * @param memoryId Message ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     */
    fun deleteMessageList(memoryId: String)

}