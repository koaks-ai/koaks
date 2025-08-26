package org.koaks.framework.net

import kotlinx.serialization.serializer
import org.koaks.framework.entity.inner.InnerChatRequest
import kotlinx.coroutines.flow.Flow
import org.koaks.framework.model.TypeAdapter

suspend inline fun <reified T> HttpClient.postAsObject(
    request: InnerChatRequest
): Result<T> = postAsObject(request, TypeAdapter(serializer(), serializer()))

inline fun <reified T> HttpClient.postAsObjectStream(
    request: InnerChatRequest
): Flow<T> = postAsObjectStream(request, TypeAdapter(serializer(), serializer()))

suspend inline fun <reified T> HttpClient.getAsObject(
    path: String
): Result<T> = getAsObject(path, serializer())