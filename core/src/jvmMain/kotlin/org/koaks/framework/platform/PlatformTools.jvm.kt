package org.koaks.framework.platform

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object PlatformUtils {
    actual fun platformType(): PlatformType {
        return PlatformType.JVM
    }
}