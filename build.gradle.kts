plugins {
    id("tech.medivh.plugin.publisher") version "1.2.5"
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

// only test publish
group = "io.github.mynna404"
version = "0.0.1-beta5"


repositories {
    mavenCentral()
}

dependencies {
    api("ch.qos.logback:logback-classic:1.5.13")
    api("io.github.oshai:kotlin-logging-jvm:7.0.3")
    api("org.reflections:reflections:0.10.2")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("com.squareup.okhttp3:okhttp:5.1.0")
    api("com.google.code.gson:gson:2.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    testImplementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

medivhPublisher {
    groupId = project.group.toString()
    artifactId = project.name
    version = project.version.toString()
    pom {
        name = "koaks"
        description =
            "A lightweight LLM development framework in JVM. It‘s another choice to langchain4j and spring-ai."
        url = "https://github.com/koaks-ai/koaks"
        licenses {
            license {
                name = "Apache-2.0 license"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "mynna404"
                name = "mynna404"
                email = "gemingjia0201@163.com"
            }
        }
        scm {
            connection = "scm:git:"
            url = "https://github.com/koaks-ai/koakst"
        }
    }
}