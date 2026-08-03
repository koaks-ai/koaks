package org.koaks.framework.annotation

/**
 * Marks a JVM method as an Agent tool.
 *
 * Java callers can pass an object containing annotated public methods directly to
 * `org.koaks.java.Agent.Builder.tool(Object)`. The builder scans the object once and
 * adapts every annotated method to the regular core Tool abstraction.
 *
 * [AnnotationTarget.CLASS] is retained for the older Kotlin/JVM [annotatedTool]
 * convenience API.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
    /** Positional description form: `@Tool("Get the weather for a city")`. */
    val value: String = "",

    /** Explicit tool name; method-level tools default to the Java method name. */
    val name: String = "",

    /** Named description form retained for source compatibility. */
    val description: String = "",
)

internal val Tool.resolvedDescription: String
    get() = value.ifBlank { description }
