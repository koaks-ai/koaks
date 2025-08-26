package org.koaks.framework.model

import kotlinx.serialization.KSerializer

data class TypeAdapter<T, R>(
    val serializer: KSerializer<T>,
    val deserializer: KSerializer<R>
)
