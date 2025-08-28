package org.koaks.framework.annotation

@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    /**
     * "params" is the parameter name of the tool, please keep it consistent with the parameter name.
     *
     * `required`
     */
    val param: String,

    /**
     * "params" is used to describe the parameters of the tool,
     *
     * `required`
     */
    val description: String,

    /**
     * "params" is used to indicate whether the parameter is required,
     *
     * `required` default: `true`
     */
    val required: Boolean = true
)
