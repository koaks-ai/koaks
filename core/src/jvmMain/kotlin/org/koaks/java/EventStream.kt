package org.koaks.java

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow as CoroutineFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.koaks.framework.loop.AgentEvent
import org.koaks.java.internal.nextVirtualThreadName

/** A single-subscription, demand-aware Java publisher for one Agent execution. */
class EventStream private constructor(
    private val source: CoroutineFlow<AgentEvent>,
) : Flow.Publisher<AgentEvent>, AutoCloseable {
    private val subscribed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<StreamSubscription?>()

    override fun subscribe(subscriber: Flow.Subscriber<in AgentEvent>) {
        if (closed.get()) {
            subscriber.onSubscribe(RejectedSubscription)
            subscriber.onError(IllegalStateException("EventStream is closed"))
            return
        }
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(RejectedSubscription)
            subscriber.onError(IllegalStateException("an EventStream supports exactly one subscriber"))
            return
        }
        lateinit var subscription: StreamSubscription
        subscription = StreamSubscription(source, subscriber) { active.compareAndSet(subscription, null) }
        active.set(subscription)
        subscriber.onSubscribe(subscription)
        if (closed.get()) subscription.cancel()
        subscription.start()
    }

    /** Subscribes a Consumer with unbounded demand and returns its completion future. */
    fun forEach(consumer: Consumer<in AgentEvent>): CompletableFuture<Void> {
        val completion = CompletableFuture<Void>()
        subscribe(object : Flow.Subscriber<AgentEvent> {
            override fun onSubscribe(subscription: Flow.Subscription) {
                completion.whenComplete { _, _ -> if (completion.isCancelled) subscription.cancel() }
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(item: AgentEvent) {
                consumer.accept(item)
            }

            override fun onError(throwable: Throwable) {
                completion.completeExceptionally(throwable)
            }

            override fun onComplete() {
                completion.complete(null)
            }
        })
        return completion
    }

    override fun close() {
        closed.set(true)
        active.get()?.cancel()
    }

    companion object {
        internal fun from(source: CoroutineFlow<AgentEvent>): EventStream = EventStream(source)
    }
}

private class StreamSubscription(
    private val source: CoroutineFlow<AgentEvent>,
    private val subscriber: Flow.Subscriber<in AgentEvent>,
    private val onFinished: () -> Unit,
) : Flow.Subscription {
    private val requested = AtomicLong()
    private val cancelled = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)
    private val demandLock = ReentrantLock()
    private val demandAvailable = demandLock.newCondition()

    @Volatile
    private var bridgeThread: Thread? = null

    fun start() {
        val thread = Thread.ofVirtual()
            .name(nextVirtualThreadName("koaks-stream"))
            .unstarted {
                try {
                    runBlocking {
                        source.collect { event ->
                            awaitDemand()
                            if (cancelled.get()) throw CancellationException("stream subscription cancelled")
                            subscriber.onNext(event)
                            consumeDemand()
                        }
                    }
                    if (!cancelled.get() && terminated.compareAndSet(false, true)) subscriber.onComplete()
                } catch (failure: Throwable) {
                    if (!cancelled.get() && terminated.compareAndSet(false, true)) subscriber.onError(failure)
                } finally {
                    onFinished()
                }
            }
        bridgeThread = thread
        if (cancelled.get()) thread.interrupt()
        thread.start()
    }

    override fun request(n: Long) {
        if (n <= 0L) {
            fail(IllegalArgumentException("Flow.Subscription.request requires n > 0"))
            return
        }
        while (true) {
            val current = requested.get()
            val next = if (current == Long.MAX_VALUE || n == Long.MAX_VALUE || current > Long.MAX_VALUE - n) {
                Long.MAX_VALUE
            } else {
                current + n
            }
            if (requested.compareAndSet(current, next)) break
        }
        demandLock.withLock { demandAvailable.signalAll() }
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        demandLock.withLock { demandAvailable.signalAll() }
        bridgeThread?.interrupt()
    }

    private fun awaitDemand() {
        demandLock.withLock {
            while (requested.get() == 0L && !cancelled.get()) demandAvailable.await()
        }
    }

    private fun consumeDemand() {
        while (true) {
            val current = requested.get()
            if (current == Long.MAX_VALUE || current == 0L) return
            if (requested.compareAndSet(current, current - 1L)) return
        }
    }

    private fun fail(failure: Throwable) {
        cancel()
        if (terminated.compareAndSet(false, true)) subscriber.onError(failure)
    }
}

private object RejectedSubscription : Flow.Subscription {
    override fun request(n: Long) = Unit
    override fun cancel() = Unit
}
