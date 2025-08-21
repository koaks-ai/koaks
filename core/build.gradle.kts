val coreArtifactId = "koaks-core"
val coreVersion = "0.0.1-preview1"

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
                api(libs.kotlin.logging)
                api(libs.ktor.client.core)
                api(libs.serialization.json)
                api(libs.coroutines.core)
                api(libs.serialization.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.ktor.client.cio)
                api(libs.logback.classic)
                api(libs.reflections)
                api(libs.kotlin.reflect)
                api(libs.coroutines.reactor)
            }
        }

        val jvmTest by getting {
            dependencies {
                dependencies {
                    implementation(libs.dotenv.kotlin)
                    implementation(libs.junit.jupiter)
                    implementation(libs.kotlin.test.junit5)
                }
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
        artifactId = coreArtifactId,
        version = coreVersion
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
