package org.koaks.framework


@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object EnvTools {

    fun loadValue(key: String): String

}
