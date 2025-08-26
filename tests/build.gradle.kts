plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        val jvmTest by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":llms:qwen"))
                implementation(libs.serialization.core)
                implementation(libs.serialization.json)
                implementation(libs.junit.jupiter)
                implementation(libs.kotlin.test.junit5)
            }
        }
    }
}

repositories {
    mavenCentral()
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        showExceptions = true
    }
}