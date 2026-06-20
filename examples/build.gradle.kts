plugins {
    id("koaks.kmp.library")
}

kotlin {
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":koaks-model:anthropic"))
                implementation(project(":koaks-model:qwen"))
                implementation(libs.dotenv)
            }
        }
    }
}
