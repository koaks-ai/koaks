import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    js(IR) {
        outputModuleName = "koaks-node-bridge"
        useEsModules()
        nodejs {
            testTask {
                useMocha {
                    timeout = "30000"
                }
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
    }
}

tasks.withType<KotlinJsCompile>().configureEach {
    compilerOptions.target.set("es2015")
}
