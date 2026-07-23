package org.koaks.framework.skill

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkdownDirectorySkillLoaderTest {
    private lateinit var fileSystem: FakeFileSystem

    @BeforeTest
    fun setUp() {
        fileSystem = FakeFileSystem().apply { emulateUnix() }
        fileSystem.createDirectories("/skills".toPath())
    }

    @AfterTest
    fun tearDown() {
        fileSystem.checkNoOpenFiles()
        fileSystem.close()
    }

    @Test
    fun discovers_front_matter_then_loads_body_and_resources() = runTest {
        writeSkill(
            "review",
            """
            ---
            name: review
            description: Review Kotlin code
            tags: [kotlin, review]
            ---
            Follow the project conventions.
            """.trimIndent(),
        )
        fileSystem.write("/skills/review/reference.md".toPath()) {
            writeUtf8("one\ntwo\nthree")
        }
        val loader = loader()

        val descriptors = loader.discover()
        val loaded = loader.load(SkillId("review"))
        val resource = loaded.resources!!.read(
            SkillResourceRequest(
                path = "reference.md",
                cursor = SkillResourceCursor(line = 2, column = 1),
                maxLines = 1,
            ),
        )

        assertEquals(listOf("review"), descriptors.map { it.id.value })
        assertEquals("Review Kotlin code", descriptors.single().description)
        assertTrue(descriptors.single().metadata.getValue("tags").contains("kotlin"))
        assertEquals("Follow the project conventions.", loaded.instructions.trim())
        assertEquals("two\n", resource.content)
        assertEquals(2, resource.firstLine)
        assertEquals(SkillResourceCursor(3, 1), resource.nextCursor)
    }

    @Test
    fun paginates_long_single_line_without_losing_content() = runTest {
        writeSkill("long", "---\nname: long\ndescription: long resource\n---\nbody")
        val original = "x".repeat(65_000)
        fileSystem.write("/skills/long/data.txt".toPath()) { writeUtf8(original) }
        val provider = loader()
            .load(SkillId("long"))
            .resources!!

        val restored = StringBuilder()
        var cursor = SkillResourceCursor(1, 1)
        do {
            val page = provider.read(
                SkillResourceRequest("data.txt", cursor = cursor, maxChars = 30_000),
            )
            restored.append(page.content)
            cursor = page.nextCursor ?: break
        } while (true)

        assertEquals(original, restored.toString())
    }

    @Test
    fun discover_reads_only_front_matter_but_load_enforces_full_file_limit() = runTest {
        writeSkill(
            "large",
            "---\nname: large\ndescription: Large body\n---\n" + "x".repeat(1_000_001),
        )
        val loader = loader()

        assertEquals("large", loader.discover().single().id.value)
        assertFailsWith<SkillException> { loader.load(SkillId("large")) }
    }

    @Test
    fun rejects_name_directory_mismatch() = runTest {
        writeSkill(
            "folder-name",
            "---\nname: another-name\ndescription: mismatch\n---\nbody",
        )

        val failure = assertFailsWith<SkillException> {
            loader().discover()
        }

        assertEquals(SkillStage.VALIDATE, failure.stage)
    }

    @Test
    fun resource_provider_rejects_traversal_and_symlink_escape() = runTest {
        writeSkill(
            "safe",
            "---\nname: safe\ndescription: safe\n---\nbody",
        )
        fileSystem.createDirectories("/outside".toPath())
        fileSystem.write("/outside/secret.txt".toPath()) { writeUtf8("secret") }
        fileSystem.createSymlink(
            "/skills/safe/link.txt".toPath(),
            "/outside/secret.txt".toPath(),
        )
        val resources = loader()
            .load(SkillId("safe"))
            .resources!!

        assertFailsWith<SkillException> {
            resources.read(SkillResourceRequest("../outside/secret.txt"))
        }
        assertFailsWith<SkillException> { resources.read(SkillResourceRequest("link.txt")) }
    }

    @Test
    fun direct_child_directory_without_skill_file_is_an_error() = runTest {
        fileSystem.createDirectories("/skills/incomplete".toPath())

        val failure = assertFailsWith<SkillException> {
            loader().discover()
        }

        assertEquals(SkillStage.DISCOVER, failure.stage)
    }

    private fun writeSkill(id: String, content: String) {
        val directory = "/skills/$id".toPath()
        fileSystem.createDirectories(directory)
        fileSystem.write(directory / "SKILL.md") { writeUtf8(content) }
    }

    private fun loader(): MarkdownDirectorySkillLoader =
        MarkdownDirectorySkillLoader("/skills", OkioSkillFileSystem(fileSystem))
}
