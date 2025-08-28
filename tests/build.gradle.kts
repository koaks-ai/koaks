import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

kotlin {
    sourceSets {

        jvmToolchain(21)

        jvm()

        js(IR) {
            nodejs {
                testTask {
                    useMocha {
                        timeout = "60s"
                    }
                    environment("PROJECT_ROOT", rootDir.absolutePath)
                }
            }
            binaries.executable()
        }

        val commonTest by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":llms:qwen"))
                implementation(libs.coroutines.test)
                implementation(kotlin("test"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.junit.jupiter)
                implementation(libs.kotlin.test.junit5)
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(npm("dotenv", "17.2.1"))
            }
        }

    }

}

// for kotlin/js nodejs
//project.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
//    // use local nodejs
//    project.extensions.getByType<NodeJsEnvSpec>().download.set(false)
//}

//rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
//    // this `download` will be removed in version 2.2, need migrate
//    // use local yarn
//    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().download =  false
//}

tasks.withType<KotlinJsCompile>().configureEach {
    compilerOptions {
        target.set("es2015")
    }
}