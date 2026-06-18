/**
 * Test-module convention: the base library setup plus the JS test runners
 * (headless Chrome via Karma in the browser, Mocha on Node). Applied only by the
 * `tests` module, replacing the old `if (target.name == "tests")` branch.
 */
plugins {
    id("koaks.kmp.library")
}

kotlin {
    js(IR) {
        browser {
            testTask {
                enabled = false
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(rootProject.file("karma"))
                }
            }
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = "0"
                }
                environment("PROJECT_ROOT", rootDir.absolutePath)
            }
        }
        binaries.executable()
    }
}
