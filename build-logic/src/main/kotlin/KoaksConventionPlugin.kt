import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider


class KoaksConventionPlugin : Plugin<Project> {

    operator fun VersionCatalog.get(alias: String): Provider<MinimalExternalModuleDependency> =
        findLibrary(alias).get()

    override fun apply(target: Project) = with(target) {

        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply("com.vanniktech.maven.publish")

        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        extensions.configure<KotlinMultiplatformExtension> {

            jvmToolchain(21)
            jvm()

            // only common dependencies for all modules
            sourceSets.getByName("commonMain").dependencies {
                api(libs["kotlin-logging"])
                api(libs["serialization-core"])
                api(libs["serialization-json"])
            }

            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }

            sourceSets.getByName("jvmMain").dependencies {
                api(libs["logback-classic"])
            }

            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs["junit-jupiter"])
                implementation(libs["kotlin-test-junit5"])
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
