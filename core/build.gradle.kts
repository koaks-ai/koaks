plugins {
    id("koaks.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.ktor.client.core)
            }
        }
        val jvmMain by getting {
            dependencies {
                api(libs.ktor.client.cio)
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
