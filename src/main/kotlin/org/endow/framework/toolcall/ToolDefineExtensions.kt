package org.endow.framework.toolcall

import org.endow.framework.utils.JsonUtil

fun ToolDefinition.toJson() = JsonUtil.toJson(this)

fun String.toToolDefinition() = JsonUtil.fromJson<ToolDefinition>(this)
