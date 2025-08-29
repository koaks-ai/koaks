import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(17)

            jvm {
                compilations.all {
                    kotlinOptions.jvmTarget = "17"
                }
            }
            if (target.name == "tests") {
                js(IR) {
                    browser {
                        testTask {
                            enabled = false
                            useKarma {
                                useChromeHeadless()
                                useConfigDirectory(project.rootProject.file("karma"))
                            }
                        }
                    }
                    nodejs {
                        testTask {
                            useMocha {
                                timeout = "0"
                            }
                            environment("PROJECT_ROOT", rootDir.absolutePath)
                        }
                    }
                    binaries.executable()
                }
            } else {
                js(IR) {
                    browser()
                    nodejs()
                }
            }

            macosArm64("macosArm")
        }

        tasks.withType<KotlinJsCompile>().configureEach {
            compilerOptions.target.set("es2015")
        }
    }
}

