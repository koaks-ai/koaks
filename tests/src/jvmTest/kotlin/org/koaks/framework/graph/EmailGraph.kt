package org.koaks.framework.graph

import kotlinx.coroutines.runBlocking
import org.koaks.graph.GraphEngine
import org.koaks.graph.createGraph
import kotlin.test.Test

class EmailGraph {

    @Test
    fun testEmailGraph() {

        val emailGraph = createGraph("email_agent") {

            // ----------- 定义节点 -----------
            model("read_email") { ctx ->
                println("1. 读取邮件")
                ctx.setValue("email_content", "用户询问如何使用 API")
            }

            model("classify_intent") { ctx ->
                println("2. 分类意图: ${ctx.getValue<String>("email_content")}")
                ctx.setValue("next_action", "search_documentation")
            }

            tool("search_documentation") { ctx ->
                println("3. 搜索文档")
                ctx.setValue("search_result", "API 使用文档")
            }

            tool("bug_tracking") { ctx ->
                println("4. 创建 Bug 工单")
                ctx.setValue("ticket_id", "TICKET-001")
            }

            model("draft_response") { ctx ->
                println("5. 起草回复...")
                val needReview = true
                ctx.setValue("draft", "这是回复内容")
                // 将路由决策存储到 state
                ctx.setValue("route_to", if (needReview) "human_review" else "send_reply")
            }

            node("human_review") { ctx ->
                println("6. 等待人工审核")
                ctx.setValue("reviewed", true)
            }

            tool("send_reply") { ctx ->
                println("7. 发送回复: ${ctx.getValue<String>("draft")}")
            }

            // ----------- 线性边 -----------
            start to "read_email"
            "read_email" to "classify_intent"

            "search_documentation" to "draft_response"
            "bug_tracking" to "draft_response"

            "human_review" to "send_reply"

            "send_reply" to end

            // ----------- 条件边 -----------
            conditional("classify_intent", router = { ctx ->
                // 从 state 中读取路由决策
                ctx.getValue<String>("next_action") ?: "draft_response"
            }) {
                "search_documentation" to "search_documentation"
                "bug_tracking" to "bug_tracking"
                "draft_response" to "draft_response"
                "human_review" to "human_review"
            }

            conditional("draft_response", router = { ctx ->
                // 从 state 中读取路由决策
                ctx.getValue<String>("route_to") ?: "send_reply"
            }) {
                "human_review" to "human_review"
                "send_reply" to "send_reply"
            }
        }

        runBlocking {
            GraphEngine(emailGraph)
                .addInterceptor(TestInterceptor())
                .execute()
            println(emailGraph.context.snapshot())
        }

    }

}