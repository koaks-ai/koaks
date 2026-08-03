package org.koaks.framework.annotation

/**
 * JVM convenience annotation marking a tool's input type (a data class) with the
 * metadata the model needs. This is **sugar only**:
 * it carries `name`/`description`, and [annotatedTool] turns an annotated input type
 * plus an `execute` lambda into a regular [org.koaks.framework.tool.Tool].
 *
 * There is NO separate reflection-based execution path — reflection is used once, at
 * registration time, only to read this annotation; execution still flows through the
 * normal `Tool<In>.execute`, identical across all platforms.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
    val name: String,
    val description: String,
)
