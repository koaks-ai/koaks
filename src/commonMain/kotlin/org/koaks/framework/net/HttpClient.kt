package org.koaks.framework.net

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.koaks.framework.entity.inner.InnerChatRequest

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class HttpClient(config: HttpClientConfig) {

    suspend fun postAsString(request: InnerChatRequest): Result<String>

    suspend fun <T> postAsObject(request: InnerChatRequest, deserializer: KSerializer<T>): Result<T>

    fun postAsStringStream(request: InnerChatRequest): Flow<String>

    fun <T> postAsObjectStream(request: InnerChatRequest, deserializer: KSerializer<T>): Flow<T>

    suspend fun get(path: String): Result<String>

    suspend fun <T> getAsObject(path: String, deserializer: KSerializer<T>): Result<T>

}