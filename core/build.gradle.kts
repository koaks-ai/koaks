plugins {
    id("koaks.multiplatform")
}

kotlin {
    sourceSets {

        // non-common dependencies for all modules
        val commonMain by getting {
            dependencies {
                api(libs.ktor.client.core)
                api(libs.coroutines.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.ktor.client.cio)
                api(libs.coroutines.reactor)
                api(libs.reflections)
                api(libs.kotlin.reflect)
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
