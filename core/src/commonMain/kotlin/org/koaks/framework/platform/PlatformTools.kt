package org.koaks.framework.platform

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object PlatformTools {

    fun platformType(): PlatformType

}