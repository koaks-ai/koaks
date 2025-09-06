plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.1.21")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.34.0")
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "koaks.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpPublishing") {
            id = "koaks.kmp.publishing"
            implementationClass = "KmpPublishConventionPlugin"
        }
        register("rootConvention") {
            id = "koaks.root.convention"
            implementationClass = "RootConventionPlugin"
        }
    }
}