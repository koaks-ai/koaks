package org.koaks.framework.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun provideEngine(): HttpClientEngineFactory<*> = Darwin
