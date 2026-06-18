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
}

tasks.withType<KotlinJsCompile>().configureEach {
    compilerOptions.target.set("es2015")
}
