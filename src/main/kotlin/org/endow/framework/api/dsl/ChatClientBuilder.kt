package org.endow.framework.api.dsl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.endow.framework.api.ChatClient
import org.endow.framework.memory.DefaultMemoryStorage
import org.endow.framework.memory.IMemoryStorage
import org.endow.framework.model.ChatModel
import org.endow.framework.model.ChatModel.ChatModelBuilder
import org.endow.framework.websearch.BingSearch
import org.endow.framework.websearch.ISearch

class ChatClientBuilder {

    val logger = KotlinLogging.logger {}

    private lateinit var model: ChatModel
    private var memory: IMemoryStorage = DefaultMemoryStorage
    private var searchEngine: ISearch = BingSearch()

    fun model(block: ChatModelBuilder.() -> Unit) {
        model = ChatModelBuilder().apply(block).build()
    }

    fun memory(block: MemoryBuilder.() -> Unit) {
        val builder = MemoryBuilder()
        builder.block()
        memory = builder.build()
    }

    fun searchEngine(block: SearchEngineBuilder.() -> Unit) {
        logger.warn { " Search engine is not supported custom yet. Using Bing Search Engine by default. " }
        searchEngine = SearchEngineBuilder().apply(block).build()
    }

    fun build(): ChatClient {
        return ChatClient(model, memory, searchEngine)
    }

}

class MemoryBuilder {
    private var storage: IMemoryStorage = DefaultMemoryStorage

    fun default() {
        storage = DefaultMemoryStorage
    }

    fun build(): IMemoryStorage = storage
}

class SearchEngineBuilder {
    private var engine: ISearch = BingSearch()

    fun bing() {
        engine = BingSearch()
    }

    fun custom(customEngine: ISearch) {
        engine = customEngine
    }

    fun build(): ISearch = engine
}

fun createChatClient(block: ChatClientBuilder.() -> Unit): ChatClient {
    val builder = ChatClientBuilder()
    builder.block()
    return builder.build()
}