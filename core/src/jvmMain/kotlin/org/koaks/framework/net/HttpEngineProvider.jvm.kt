package org.koaks.framework.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun provideEngine(): HttpClientEngineFactory<*> = OkHttp