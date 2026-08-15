import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

@CacheableTask
abstract class NodePackagePrepareTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val npmSource: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinDistribution: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val packageVersion: Property<String>

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        fileSystemOperations.sync {
            from(npmSource)
            from(kotlinDistribution) { into("internal") }
            into(outputDirectory)
        }

        val output = outputDirectory.get().asFile
        output.resolve("internal/koaks-node-bridge.d.ts").copyTo(
            output.resolve("internal/koaks-node-bridge.d.mts"),
            overwrite = true,
        )
        val packageFile = output.resolve("package.json")
        val generatedPackageFile = output.resolve("internal/package.json")
        @Suppress("UNCHECKED_CAST")
        val publicPackage = JsonSlurper().parse(packageFile) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val generatedPackage = JsonSlurper().parse(generatedPackageFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val publicDependencies = publicPackage["dependencies"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val generatedDependencies = generatedPackage["dependencies"] as? Map<String, Any?> ?: emptyMap()
        publicPackage["version"] = packageVersion.get()
        publicPackage["dependencies"] = generatedDependencies + publicDependencies
        packageFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(publicPackage)) + "\n")
    }
}
