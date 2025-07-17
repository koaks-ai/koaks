package org.endow.framework.memory

import org.endow.framework.entity.Message

interface IMemoryStorage {

    /**
     * get message list by message id，it contains all the message records within the length range in this conversation.
     * @param msgId Message ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     * @return MutableList<Message>
     */
    fun getMessages(msgId: String): MutableList<Message>

    /**
     * add message to message list by message id.
     * @param msgId Message ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     * @param message Message
     */
    fun addMessage(msgId: String, message: Message)

    /**
     * delete message list by message id.
     * @param msgId Message ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     */
    fun deleteMessages(msgId: String)

    /**
     * get memory list by memory id. This memory ID should be limited to each conversation.
     * it contains all the memory records in this conversation.
     * This function is called during each conversation to store the memory in the SystemMessage.
     * @param memoryId Memory ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     * @return MutableList<String>
     */
    fun getMemory(memoryId: String): MutableList<String>

    /**
     * add memory to memory list by memory id.
     * @param memoryId Memory ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     * @param memory Memory
     */
    fun addMemory(memoryId: String, memory: String)

    /**
     * delete memory list by memory id.
     * @param memoryId Memory ID, this is the unique identifier for a particular continuous conversation, it should be unique.
     */
    fun deleteMemory(memoryId: String)

}