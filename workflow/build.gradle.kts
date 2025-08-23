plugins {
    id("koaks.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "koaks-workflow",
        version = project.version.toString()
    )
    pom {
        name.set("koaks-workflow")
    }
}