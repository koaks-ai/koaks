allprojects {
    group = "io.github.mynna404"
    version = "0.0.1-preview3"

    repositories {
        mavenCentral()
        google()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            showStandardStreams = true
            showExceptions = true
        }
    }

}
