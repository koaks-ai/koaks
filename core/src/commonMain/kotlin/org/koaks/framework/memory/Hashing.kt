package org.koaks.framework.memory

/** FNV-1a 64-bit hex digest. Deterministic, not cryptographic. */
fun fnv1aHex(s: String): String {
    var hash = -3750763034362895579L
    val prime = 1099511628211L
    for (c in s) {
        hash = hash xor c.code.toLong()
        hash *= prime
    }
    return "${hash.toULong().toString(16)}-${s.length}"
}
