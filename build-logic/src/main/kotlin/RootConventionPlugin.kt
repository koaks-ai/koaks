import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.repositories

class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        subprojects {
            group = "io.github.mynna404"
            version = "0.0.1-preview5"

            repositories {
                mavenCentral()
                google()
            }

        }
    }
}