import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.repositories

class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        subprojects {
            group = "org.koaks.framework"
            version = "0.0.1-snapshot1"

            repositories {
                mavenCentral()
                google()
            }

        }
    }
}