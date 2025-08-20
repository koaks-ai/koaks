plugins {
//    id("tech.medivh.plugin.publisher") version "1.2.5"
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

// only test publish
group = "io.github.mynna404"
version = "0.0.1-beta6"


repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm {
//        testRuns["test"].executionTask.configure {
//            useJUnitPlatform()
//        }
    }

//    js(IR) {
//        browser()
//        nodejs()
//    }

    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("io.github.oshai:kotlin-logging:7.0.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("ch.qos.logback:logback-classic:1.5.13")
                implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
                implementation("org.reflections:reflections:0.10.2")
                implementation("org.jetbrains.kotlin:kotlin-reflect")
                implementation("com.squareup.okhttp3:okhttp:5.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
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
//                implementation("io.github.oshai:kotlin-logging-js:7.0.3")
//                implementation("io.ktor:ktor-client-js:2.3.11")
//            }
//        }
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

//medivhPublisher {
//    groupId = project.group.toString()
//    artifactId = project.name
//    version = project.version.toString()
//    pom {
//        name = "koaks"
//        description =
//            "A lightweight LLM development framework in JVM. It‘s another choice to langchain4j and spring-ai."
//        url = "https://github.com/koaks-ai/koaks"
//        licenses {
//            license {
//                name = "Apache-2.0 license"
//                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
//            }
//        }
//        developers {
//            developer {
//                id = "mynna404"
//                name = "mynna404"
//                email = "gemingjia0201@163.com"
//            }
//        }
//        scm {
//            connection = "scm:git:"
//            url = "https://github.com/koaks-ai/koakst"
//        }
//    }
//}