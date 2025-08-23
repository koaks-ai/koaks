package org.koaks.framework.annotation

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
    /**
     * "description" is used to describe the function of the tool,
     * and the llm uses it to determine whether the tool should be used.
     *
     * you should describe the function of the tool as clearly as possible,
     * as it plays a crucial role in the accuracy of tool invocation by the llm.
     *
     * `required`
     */
    val description: String,

    /**
     * "params" is used to add the parameters of the tool.
     *
     * `optional`
     */
    val params: Array<Param> = [],

    /**
     * "group" is the grouping information of the tool. It is optional, but it is recommended to set this attribute,
     * because in this way you can put all tools directly into the client and then set the group() information.
     * The framework will select tools based on the group, which can save users from the trouble of manually managing tools.
     *
     * `optional`
     */
    val group: String = "default",
)
