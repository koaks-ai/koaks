plugins {
    id("koaks.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "koaks-qwen",
        version = project.version.toString()
    )
    pom {
        name.set("koaks-qwen")
    }
}