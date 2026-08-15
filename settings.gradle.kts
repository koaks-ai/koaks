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
include("model-provider:chat-completions")
include("model-provider:qwen")
include("model-provider:ollama")
include("model-provider:openai")
include("model-provider:anthropic")
include("koaks-memory:summarizing")
include("koaks-memory:vector")
include("interop:node")
include("tests")
include("examples")
