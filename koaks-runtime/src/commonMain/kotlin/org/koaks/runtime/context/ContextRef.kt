package org.koaks.runtime.context

import kotlin.jvm.JvmInline

/**
 * A handle to a stored context block. Messages carry [ContextRef]s instead of copying
 * large context payloads around — the receiver resolves the ref (by permission) from the
 * [ContextStore]. In Phase 3 it is a plain id; Phase 4 backs it with content-addressed,
 * copy-on-write storage.
 */
@JvmInline
value class ContextRef(val id: String)
