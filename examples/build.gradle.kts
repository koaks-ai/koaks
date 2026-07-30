import org.gradle.api.tasks.JavaExec

plugins {
    id("koaks.kmp.library")
}

kotlin {
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":koaks-memory:summarizing"))
                implementation(project(":koaks-model:anthropic"))
                implementation(project(":koaks-model:openai"))
                implementation(libs.dotenv)
            }
        }
    }
}

val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runRuntimeShowcase") {
    group = "application"
    description = "Runs the API-key-free Koaks Agent Runtime capability showcase"
    mainClass.set("examples.KoaksRuntimeShowcaseKt")
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    dependsOn(jvmMain.compileTaskProvider)
}

tasks.register<JavaExec>("runRuntimeStressTest") {
    group = "application"
    description = "Runs the standalone Koaks Agent Runtime mass-scheduling stress test"
    mainClass.set("examples.KoaksRuntimeStressTestKt")
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    dependsOn(jvmMain.compileTaskProvider)
}
