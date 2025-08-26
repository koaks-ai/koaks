import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

class KoaksConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {

        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply("com.vanniktech.maven.publish")

        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            jvm()

            sourceSets.getByName("commonMain").dependencies {
                api(libs.findLibrary("kotlin-logging").get())
                api(libs.findLibrary("coroutines-core").get())
                api(libs.findLibrary("serialization-core").get())
                api(libs.findLibrary("serialization-json").get())
                api(libs.findLibrary("ktor-client-core").get())
            }

            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }

            sourceSets.getByName("jvmMain").dependencies {
                api(libs.findLibrary("logback-classic").get())
                api(libs.findLibrary("coroutines-reactor").get())
                api(libs.findLibrary("ktor-client-cio").get())
                api(libs.findLibrary("reflections").get())
                api(libs.findLibrary("kotlin-reflect").get())
            }

            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.findLibrary("junit-jupiter").get())
                implementation(libs.findLibrary("kotlin-test-junit5").get())
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                showStandardStreams = true
                showExceptions = true
            }
        }

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            pom {
                description.set(
                    "A LLM development framework for Kotlin Multiplatform. " +
                            "Lightweight alternative to langchain4j and spring-ai on JVM."
                )
                inceptionYear.set("2025")
                url.set("https://github.com/koaks-ai/koaks")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("mynna404")
                        name.set("TruE")
                        url.set("https://github.com/mynna404")
                    }
                }
                scm {
                    url.set("https://github.com/koaks-ai/koaks")
                    connection.set("scm:git:https://github.com/koaks-ai/koaks.git")
                    developerConnection.set("scm:git:https://github.com/koaks-ai/koaks.git")
                }
            }
        }
    }

}