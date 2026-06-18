plugins {
    `kotlin-dsl`
}

dependencies {
    // Gradle plugins consumed as artifacts so the precompiled script plugins
    // can apply them by id. Versions come from the shared catalog — no drift.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationPlugin)
    implementation(libs.mavenPublish.plugin)
}
