package org.koaks.framework.net

interface HttpCallback<T> {
    fun onStart() {}
    fun onSuccess(data: T)
    fun onError(exception: Throwable)
    fun onComplete() {}
}

interface HttpStreamCallback<T> {
    fun onStart() {}
    fun onNext(item: T)
    fun onError(exception: Throwable)
    fun onComplete() {}
}