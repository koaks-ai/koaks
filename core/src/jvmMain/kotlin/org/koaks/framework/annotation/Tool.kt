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

    /**
     * By default, the results of tool calls are sent back to the model as responses.
     * The model can then use these results to continue the conversation.
     *
     * In certain scenarios, you may want to return the results directly to the caller instead of sending them back to the model.
     * For example, if you're building an agent that relies on a RAG (Retrieval-Augmented Generation) tool,
     * you may prefer to return the results directly to the caller rather than sending them back to the model for unnecessary
     * post-processing. Alternatively, you may have certain tools that should terminate the agent's reasoning loop.
     */
    val returnDirectly: Boolean = false
)
