import com.vanniktech.maven.publish.MavenPublishBaseExtension

/**
 * Publishing convention: applies the vanniktech maven-publish plugin and the
 * shared POM (license, scm, developer). Per-module `mavenPublishing { coordinates(...) }`
 * blocks add the artifact id on top — the extension accessor stays available there
 * because this plugin applies the underlying vanniktech plugin.
 */
plugins {
    id("com.vanniktech.maven.publish")
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral()
    signAllPublications()

    pom {
        description.set(
            "An Agentic AI framework for Kotlin Multiplatform."
        )
        inceptionYear.set("2025")
        url.set("https://github.com/koaks-ai/koaks")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("mynna404")
                name.set("TruE")
                url.set("https://github.com/mynna404")
            }
        }
        scm {
            url.set("https://github.com/koaks-ai/koaks")
            connection.set("scm:git:https://github.com/koaks-ai/koaks.git")
            developerConnection.set("scm:git:https://github.com/koaks-ai/koaks.git")
        }
    }
}
