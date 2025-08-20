package org.koaks.framework.toolcall

import org.koaks.framework.utils.JsonUtil

fun ToolDefinition.toJson() = JsonUtil.toJson(this)

fun String.toToolDefinition() = JsonUtil.fromJson<ToolDefinition>(this)
