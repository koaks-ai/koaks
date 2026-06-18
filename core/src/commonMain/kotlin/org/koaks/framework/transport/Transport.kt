package org.koaks.framework.transport

import kotlinx.coroutines.flow.Flow
import org.koaks.framework.provider.WireAdapter

/**
 * The L0 transport: streams a serialized request to a provider endpoint and emits
 * decoded response chunks. Pluggable; the default is [KtorTransport].
 *
 * Owns its underlying HTTP resources, hence [AutoCloseable]. An [org.koaks.framework.loop.Agent]
 * that creates its own transport closes it; an externally-injected transport is the
 * caller's to close.
 */
interface Transport : AutoCloseable {
    fun <TReq, TResp> stream(
        config: ModelConfig,
        req: TReq,
        adapter: WireAdapter<TReq, TResp>,
    ): Flow<TResp>
}
