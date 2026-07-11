plugins {
    id("koaks.kmp.test")
}

kotlin {
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":runtime"))
                implementation(project(":koaks-model:qwen"))
                implementation(project(":koaks-model:ollama"))
                implementation(project(":koaks-model:openai"))
                implementation(project(":koaks-model:anthropic"))
                implementation(project(":koaks-memory:summarizing"))
                implementation(project(":koaks-memory:vector"))
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.dotenv)
                implementation(libs.junit.jupiter)
                implementation(libs.kotlin.test.junit5)
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(npm("dotenv", "17.2.1"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        showExceptions = true
    }
}
