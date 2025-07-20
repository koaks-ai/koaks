package org.endowx.framework.toolcall

import org.endowx.framework.utils.JsonUtil

fun ToolDefinition.toJson() = JsonUtil.toJson(this)

fun String.toToolDefinition() = JsonUtil.fromJson<ToolDefinition>(this)
