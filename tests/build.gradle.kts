plugins {
    id("koaks.multiplatform")
}

kotlin {
    sourceSets {
        val jvmTest by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":llms:qwen"))
            }
        }
    }
}