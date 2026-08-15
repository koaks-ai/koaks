plugins {
    id("koaks.kmp.library")
    id("koaks.kmp.publishing")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                api(project(":model-provider:chat-completions"))
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "provider-qwen",
        version = project.version.toString()
    )
    pom {
        name.set("provider-qwen")
    }
}