package org.koaks.framework

@JsModule("dotenv")
@JsNonModule
external object Dotenv {
    fun config(options: dynamic = definedExternally): dynamic
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object EnvTools {
    actual fun loadValue(key: String): String {
        Dotenv.config()
        return js("process.env[key]") as String
    }
}

fun main() {
    console.log(EnvTools.loadValue("BASE_URL"))
}