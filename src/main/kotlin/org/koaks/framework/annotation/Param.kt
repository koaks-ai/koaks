package org.koaks.framework.annotation

@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    val param: String,
    val description: String,
    val required: Boolean = true
)
