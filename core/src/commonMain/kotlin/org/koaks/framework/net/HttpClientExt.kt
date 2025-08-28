package org.koaks.framework.net

import kotlinx.serialization.serializer
import kotlinx.coroutines.flow.Flow
import org.koaks.framework.model.TypeAdapter

suspend inline fun <reified T, reified R> KtorHttpClient.postAsObject(
    request: T
): Result<R> = postAsObject(request, TypeAdapter(serializer(), serializer()))

inline fun <reified T, reified R> KtorHttpClient.postAsObjectStream(
    request: T
): Flow<R> = postAsObjectStream(request, TypeAdapter(serializer(), serializer()))

suspend inline fun <reified R> KtorHttpClient.getAsObject(
    path: String
): Result<R> = getAsObject(path, serializer())
