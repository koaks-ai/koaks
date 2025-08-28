@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.framework

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@JsModule("dotenv")
@JsNonModule
external object Dotenv {
    fun config(options: dynamic = definedExternally): dynamic
}


actual object EnvTools {

    actual fun loadValue(key: String): String {
        Dotenv.config(js("{ path: process.env.PROJECT_ROOT + '/.env' }"))
        return try {
            js("process.env[key]")
        } catch (e: Throwable) {
            logger.error { "Error loading environment variable: $key" }
            ""
        }
    }
}
