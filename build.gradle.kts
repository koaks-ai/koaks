plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

subprojects {

    apply {
        plugin("kotlin-multiplatform")
        plugin("kotlinx-serialization")
        plugin("com.vanniktech.maven.publish")
    }

    // only test publish
    group = "io.github.mynna404"
    version = "0.0.1-preview1"

    repositories {
        mavenCentral()
    }

}
