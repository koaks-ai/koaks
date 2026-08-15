plugins {
    id("koaks.js.node")
}

kotlin {
    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":model-provider:chat-completions"))
                implementation(project(":model-provider:qwen"))
                implementation(project(":model-provider:ollama"))
                implementation(project(":model-provider:openai"))
                implementation(project(":model-provider:anthropic"))
                implementation(project(":koaks-memory:summarizing"))
                implementation(project(":koaks-memory:vector"))
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
            }
        }
    }
}

val npmDir = layout.projectDirectory.dir("npm")
val npmBuildDir = layout.buildDirectory.dir("npm-package")
val nodePackageVersion = version.toString()
val npmExecutable = providers.gradleProperty("koaks.npmExecutable")
    .orElse(providers.environmentVariable("NPM_EXECUTABLE"))
    .orElse("npm")
val nodeExecutable = providers.gradleProperty("koaks.nodeExecutable")
    .orElse(providers.environmentVariable("NODE_EXECUTABLE"))
    .orElse("node")

val prepareNpmPackage by tasks.registering(NodePackagePrepareTask::class) {
    dependsOn("jsNodeProductionLibraryDistribution")
    npmSource.set(npmDir)
    kotlinDistribution.set(layout.buildDirectory.dir("dist/js/productionLibrary"))
    outputDirectory.set(npmBuildDir)
    packageVersion.set(nodePackageVersion)
}

val npmInstall by tasks.registering(Exec::class) {
    dependsOn(prepareNpmPackage)
    workingDir(npmBuildDir)
    commandLine(npmExecutable.get(), "install", "--ignore-scripts", "--no-audit", "--no-fund")
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    workingDir(npmBuildDir)
    commandLine(npmExecutable.get(), "run", "build")
}

val npmTest by tasks.registering(Exec::class) {
    dependsOn(npmBuild)
    workingDir(npmBuildDir)
    commandLine(npmExecutable.get(), "test")
}

val npmPack by tasks.registering(Exec::class) {
    dependsOn(npmTest)
    workingDir(npmBuildDir)
    commandLine(npmExecutable.get(), "pack")
}

val npmPackageTest by tasks.registering(Exec::class) {
    dependsOn(npmPack)
    workingDir(npmBuildDir)
    environment("KOAKS_NPM_EXECUTABLE", npmExecutable.get())
    commandLine(nodeExecutable.get(), npmBuildDir.get().file("package-test.mjs").asFile, npmBuildDir.get().asFile)
}

tasks.register("checkNodePackage") {
    group = "verification"
    dependsOn("jsNodeTest", npmPackageTest)
}
