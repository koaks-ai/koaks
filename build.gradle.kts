plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

group = "org.endowx.framework"
version = "0.0.1-SNAPSHOT"


repositories {
    mavenCentral()
}


dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("org.reflections:reflections:0.10.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework:spring-webflux:6.2.3")
    implementation("org.jsoup:jsoup:1.21.1")
//    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}