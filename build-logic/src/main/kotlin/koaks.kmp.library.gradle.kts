import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

/**
 * Base convention for every Koaks KMP library module: targets, toolchain and the
 * serialization plugin. Test-only JS wiring (karma/mocha) lives in the separate
 * `koaks.kmp.test` convention so this plugin stays free of module-name special-casing.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    // The toolchain fixes both the compile JDK and the resulting jvmTarget, so no
    // explicit `jvmTarget` is needed (and `kotlinOptions` is deprecated in Kotlin 2.x).
    jvmToolchain(17)

    jvm()

    js(IR) {
        browser()
        nodejs()
    }

    macosArm64("macosArm")
    mingwX64("windowsX64")

    // iOS shares all Apple actuals (Darwin engine, posix env, platform type) with
    // macOS via the default hierarchy template's auto-generated `appleMain` source set.
    iosArm64()
    iosSimulatorArm64()
}

tasks.withType<KotlinJsCompile>().configureEach {
    compilerOptions.target.set("es2015")
}

val utf8JvmEncodingArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
)

tasks.withType<JavaExec>().configureEach {
    jvmArgs(utf8JvmEncodingArgs)
}

tasks.withType<Test>().configureEach {
    jvmArgs(utf8JvmEncodingArgs)
}
