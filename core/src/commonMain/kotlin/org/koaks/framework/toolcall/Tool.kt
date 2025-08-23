package org.koaks.framework.toolcall

interface Tool<T> {
    /**
     * "tool name" is a `unique identifier` for a tool and must not be repeated.
     *
     * `required`
     */
    val name: String

    /**
     * "description" is used to describe the function of the tool,
     * and the llm uses it to determine whether the tool should be used.
     *
     * you should describe the function of the tool as clearly as possible,
     * as it plays a crucial role in the accuracy of tool invocation by the llm.
     *
     * `required`
     */
    val description: String

    /**
     * "group" is the grouping information of the tool. It is optional, but it is recommended to set this attribute,
     * because in this way you can put all tools directly into the client and then set the group() information.
     * The framework will select tools based on the group, which can save users from the trouble of manually managing tools.
     *
     * `optional`
     */
    val group: String?

    /**
     * "execute" is the function that actually executes the tool.
     */
    suspend fun execute(input: T): String
}
