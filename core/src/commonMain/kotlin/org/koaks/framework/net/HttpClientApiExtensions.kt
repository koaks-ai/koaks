package org.koaks.framework.net

import kotlinx.serialization.serializer
import org.koaks.framework.entity.inner.InnerChatRequest
import kotlinx.coroutines.flow.Flow

suspend inline fun <reified T> HttpClient.postAsObject(
    request: InnerChatRequest
): Result<T> = postAsObject(request, serializer())

inline fun <reified T> HttpClient.postAsObjectStream(
    request: InnerChatRequest
): Flow<T> = postAsObjectStream(request, serializer())

suspend inline fun <reified T> HttpClient.getAsObject(
    path: String
): Result<T> = getAsObject(path, serializer())