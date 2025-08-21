plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
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
