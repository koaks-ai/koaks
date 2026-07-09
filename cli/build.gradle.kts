import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("koaks.kmp.library")
}

kotlin {
    targets.named<KotlinNativeTarget>("windowsX64") {
        binaries {
            executable {
                baseName = "koaks"
                entryPoint = "org.koaks.cli.main"
            }
        }
    }

    targets.named<KotlinNativeTarget>("macosArm") {
        binaries {
            executable {
                baseName = "koaks"
                entryPoint = "org.koaks.cli.main"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":koaks-model:anthropic"))
                implementation(project(":koaks-model:ollama"))
                implementation(project(":koaks-model:openai"))
                implementation(project(":koaks-model:qwen"))
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val appleMain by creating {
            dependsOn(nativeMain)
        }
        val windowsX64Main by getting {
            dependsOn(nativeMain)
        }
        val macosArmMain by getting {
            dependsOn(appleMain)
        }
        val iosArm64Main by getting {
            dependsOn(appleMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(appleMain)
        }
        val commonTest by getting
        val nativeTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val windowsX64Test by getting {
            dependsOn(nativeTest)
        }
        val macosArmTest by getting {
            dependsOn(nativeTest)
        }
        val iosArm64Test by getting {
            dependsOn(nativeTest)
        }
        val iosSimulatorArm64Test by getting {
            dependsOn(nativeTest)
        }
    }
}
