pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "koaks"

include("core")
include("graph")
include("koaks-model:qwen")
// TODO(Stage 2 / Phase 1): migrate ollama provider to the new ChatModel base, then re-enable.
// include("koaks-model:ollama")
include("tests")
