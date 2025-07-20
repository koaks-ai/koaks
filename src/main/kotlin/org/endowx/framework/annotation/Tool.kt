package org.endowx.framework.annotation

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
    val description: String,
    val params: Array<Param> = [],
    val group: String = "default",
)
