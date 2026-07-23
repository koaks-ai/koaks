package org.koaks.framework.skill

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeMarkdownDirectorySkillLoaderTest {
    @Test
    fun default_directory_loader_reads_node_file_system() = runTest {
        val importer: dynamic = js("new Function('specifier', 'return import(specifier)')")
        val fs = (importer("node:fs") as Promise<dynamic>).await()
        val path = (importer("node:path") as Promise<dynamic>).await()
        val os = (importer("node:os") as Promise<dynamic>).await()
        val root = path.join(os.tmpdir(), "koaks-node-skills-${Random.nextInt(0, Int.MAX_VALUE)}") as String
        val skillRoot = path.join(root, "node-skill") as String
        try {
            fs.mkdirSync(skillRoot, js("({ recursive: true })"))
            fs.writeFileSync(
                path.join(skillRoot, "SKILL.md"),
                "---\nname: node-skill\ndescription: Node Skill\n---\nLoaded from Node.",
                "utf8",
            )

            val loader = MarkdownDirectorySkillLoader(root)
            val descriptors = loader.discover()
            val definition = loader.load(SkillId("node-skill"))

            assertEquals(listOf("node-skill"), descriptors.map { it.id.value })
            assertEquals("Loaded from Node.", definition.instructions)
        } finally {
            fs.rmSync(root, js("({ recursive: true, force: true })"))
        }
    }
}
