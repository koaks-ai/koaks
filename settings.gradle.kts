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

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "koaks"

include("core")
include("koaks-model:qwen")
include("koaks-model:ollama")
include("koaks-memory:summarizing")
include("koaks-memory:vector")
include("tests")
include("examples")
