package org.koaks.framework.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.winhttp.WinHttp

actual fun provideEngine(): HttpClientEngineFactory<*> = WinHttp
