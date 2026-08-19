plugins {
    id("koaks.kmp.library")
    id("koaks.kmp.publishing")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                api(libs.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "koaks-json",
        version = project.version.toString(),
    )
    pom {
        name.set("koaks-json")
        description.set("Common Koaks interop JSON codecs")
    }
}
