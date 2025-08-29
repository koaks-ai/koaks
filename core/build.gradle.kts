plugins {
    id("koaks.kmp.library")
    id("koaks.kmp.publishing")
}

kotlin {
    sourceSets {
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
        val macosArmMain by getting {
            dependencies {
                api(libs.ktor.client.darwin)
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "koaks-core",
        version = project.version.toString()
    )
    pom {
        name.set("koaks-core")
    }
}

