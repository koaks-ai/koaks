package org.koaks.framework.net

import io.ktor.client.engine.HttpClientEngineFactory

expect fun provideEngine(): HttpClientEngineFactory<*>