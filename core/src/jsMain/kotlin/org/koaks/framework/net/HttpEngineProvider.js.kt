package org.koaks.framework.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

actual fun provideEngine(): HttpClientEngineFactory<*> = Js