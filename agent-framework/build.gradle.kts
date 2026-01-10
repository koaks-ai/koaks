plugins {
    id("koaks.kmp.library")
    id("koaks.kmp.publishing")
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
        artifactId = "koaks-agent",
        version = project.version.toString()
    )
    pom {
        name.set("koaks-agent")
    }
}