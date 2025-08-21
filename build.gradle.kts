plugins {
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("com.vanniktech.maven.publish") version "0.34.0"
}

// only test publish
group = "io.github.mynna404"
version = "0.0.1-preview1"


repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm()

//    js(IR) {
//        nodejs()
//    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                api("io.github.oshai:kotlin-logging:7.0.3")
                api("io.ktor:ktor-client-core:3.2.3")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                api("io.ktor:ktor-client-cio:3.2.3")
                api("ch.qos.logback:logback-classic:1.5.13")
                api("org.reflections:reflections:0.10.2")
                api("org.jetbrains.kotlin:kotlin-reflect")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
                implementation("org.junit.jupiter:junit-jupiter:5.13.4")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
            }
        }

//        val jsMain by getting {
//            dependencies {
//                implementation("io.ktor:ktor-client-js:3.2.3")
//            }
//        }
//
//        val jsTest by getting {
//            dependencies {
//                implementation("org.jetbrains.kotlin:kotlin-test-js")
//            }
//        }

    }

}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        showExceptions = true
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "koaks",
        version = version.toString()
    )

    pom {
        name = "koaks"
        description =
            "A LLM development framework for kotlin multiplatform. It's also a lightweight alternative to langchain4j and spring-ai on JVM."
        inceptionYear = "2025"
        url = "https://github.com/koaks-ai/koaks"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "mynna404"
                name = "TruE"
                url = "https://github.com/mynna404"
            }
        }
        scm {
            url = "https://github.com/koaks-ai/koaks"
            connection = "scm:git:git:https://github.com/koaks-ai/koaks.git"
            developerConnection = "scm:git:https://github.com/koaks-ai/koaks.git"
        }
    }
}
