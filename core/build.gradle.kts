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
        js(IR){
            nodejs {}
        }

        val commonMain by getting {
            dependencies {
                api(libs.ktor.client.core)
                api(libs.coroutines.core)
                api(libs.kotlin.logging)
                api(libs.serialization.core)
                api(libs.serialization.json)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.ktor.client.okhttp)
//                api(libs.coroutines.reactor)
                api(libs.logback.classic)
                api(libs.reflections)
                api(libs.kotlin.reflect)
            }
        }


        val jsMain by getting {
            dependencies {
                api(libs.ktor.client.js)
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
//    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().download =  false
//}

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
        artifactId = "koaks-core",
        version = project.version.toString()
    )

    pom {
        name.set("koaks-core")

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
