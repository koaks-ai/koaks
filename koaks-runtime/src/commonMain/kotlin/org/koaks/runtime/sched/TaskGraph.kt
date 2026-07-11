package org.koaks.runtime.sched

import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult

/**
 * A node in a (dynamic) task DAG: an agent to run, its priority, the ids it depends on,
 * and an [input] builder that can read the results of its dependencies.
 */
class TaskNode internal constructor(
    val id: String,
    val agent: Agent,
    val priority: Int,
    val dependsOn: List<String>,
    val input: suspend (deps: Map<String, AgentResult>) -> String,
)

/**
 * A validated task DAG. Independent nodes run concurrently (subject to the scheduler's
 * concurrency cap); a node starts only once all of its dependencies have finished. Build
 * one with [taskGraph].
 */
class TaskGraph internal constructor(val nodes: List<TaskNode>)

/** DSL builder for a [TaskGraph], validating ids, dependencies, and acyclicity. */
class TaskGraphBuilder {
    private val nodes = mutableListOf<TaskNode>()

    /** Adds a task with a static [input]. */
    fun task(
        id: String,
        agent: Agent,
        input: String,
        priority: Int = 0,
        dependsOn: List<String> = emptyList(),
    ) = task(id, agent, priority, dependsOn) { input }

    /** Adds a task whose [input] is computed from its dependency results. */
    fun task(
        id: String,
        agent: Agent,
        priority: Int = 0,
        dependsOn: List<String> = emptyList(),
        input: suspend (deps: Map<String, AgentResult>) -> String,
    ) {
        require(nodes.none { it.id == id }) { "duplicate task id: $id" }
        nodes += TaskNode(id, agent, priority, dependsOn, input)
    }

    fun build(): TaskGraph {
        val ids = nodes.map { it.id }.toSet()
        nodes.forEach { node ->
            node.dependsOn.forEach { dep ->
                require(dep in ids) { "task '${node.id}' depends on unknown task '$dep'" }
                require(dep != node.id) { "task '${node.id}' cannot depend on itself" }
            }
        }
        detectCycle(nodes)
        return TaskGraph(nodes.toList())
    }

    private fun detectCycle(nodes: List<TaskNode>) {
        val byId = nodes.associateBy { it.id }
        val visiting = HashSet<String>()
        val done = HashSet<String>()

        fun dfs(id: String) {
            if (id in done) return
            require(id !in visiting) { "task graph has a cycle involving '$id'" }
            visiting += id
            byId.getValue(id).dependsOn.forEach { dfs(it) }
            visiting -= id
            done += id
        }

        nodes.forEach { dfs(it.id) }
    }
}

/** Builds a [TaskGraph] from a DSL block. */
fun taskGraph(block: TaskGraphBuilder.() -> Unit): TaskGraph = TaskGraphBuilder().apply(block).build()
