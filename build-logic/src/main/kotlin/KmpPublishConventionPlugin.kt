import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import com.vanniktech.maven.publish.MavenPublishBaseExtension

class KmpPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.vanniktech.maven.publish")

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