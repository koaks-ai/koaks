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
include("koaks-model:ollama")
include("koaks-memory:summarizing")
include("koaks-memory:vector")
include("tests")
