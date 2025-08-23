package org.koaks.workflow.node

enum class NodeType(val type: String) {
    START("start"),

    END("end"),

    SWITCH("switch"),

    LOOP("loop"),

    ERROR("error"),

    SUBFLOW("subflow");

}
