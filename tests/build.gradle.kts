plugins {
    id("koaks.kmp.library")
}

kotlin {
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":llms:qwen"))
                implementation(libs.coroutines.test)
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
