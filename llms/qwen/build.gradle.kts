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
        js {
            nodejs {}
        }

        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
            }
        }

    }
}

tasks.withType<KotlinJsCompile>().configureEach {
    compilerOptions {
        target.set("es2015")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = project.group.toString(),
        artifactId = "koaks-qwen",
        version = project.version.toString()
    )

    pom {
        name.set("koaks-qwen")

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