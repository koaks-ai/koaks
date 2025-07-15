package org.endow.framework.entity

import kotlinx.coroutines.flow.Flow


class ModelResponse<T> {
    private var stream: Flow<T?>? = null

    private var response: T? = null

    fun stream(): Flow<T?>? {
        return stream
    }

    fun response(): T? {
        return response
    }

    fun setStream(stream: Flow<T?>?) {
        this.stream = stream
    }

    fun setResponse(response: T?) {
        this.response = response
    }
}