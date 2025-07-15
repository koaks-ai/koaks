package org.endow.framework.net

interface ICallback<R> {
    /**
     * 用于流式响应，每接收到一部分数据时调用。
     * @param data 解析后的数据块
     */
    fun onNext(data: R) {}

    /**
     * 用于单次、同步的成功响应。
     * @param data 完整的、解析后的数据
     */
    fun onSuccess(data: R) {}

    /**
     * 当请求发生任何错误时调用。
     * @param error 发生的异常
     */
    fun onError(error: Throwable)

    /**
     * 当流式响应结束时调用，无论成功或失败。
     */
    fun onFinish() {}
}

/**
 * HTTP 请求回调接口
 */
interface HttpCallback<T> {
    fun onStart() {}
    fun onSuccess(data: T)
    fun onError(exception: Throwable)
    fun onComplete() {}
}

/**
 * HTTP 流请求回调接口
 */
interface HttpStreamCallback<T> {
    fun onStart() {}
    fun onNext(item: T)
    fun onError(exception: Throwable)
    fun onComplete() {}
}