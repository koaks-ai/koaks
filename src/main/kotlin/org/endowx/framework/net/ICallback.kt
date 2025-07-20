package org.endowx.framework.net

interface ICallback<R> {

    fun onNext(data: R) {}

    fun onSuccess(data: R) {}

    fun onError(error: Throwable)

    fun onFinish() {}
}

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