package org.koaks.framework.transport

import kotlinx.serialization.KSerializer

/**
 * The KSerializer pair a provider supplies so the [Transport] can serialize the
 * request body and deserialize each streamed response chunk, without the transport
 * knowing the concrete wire types.
 */
class WireAdapter<TReq, TResp>(
    val requestSerializer: KSerializer<TReq>,
    val responseSerializer: KSerializer<TResp>,
)
