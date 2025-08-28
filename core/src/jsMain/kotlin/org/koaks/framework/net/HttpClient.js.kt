package org.koaks.framework.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

actual fun createHttpClient(config: HttpClientConfig): HttpClient =
    HttpClient(Js) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.callTimeout * 1000
            connectTimeoutMillis = config.connectTimeout * 1000
            socketTimeoutMillis = config.readTimeout * 1000
        }
        defaultRequest {
            url(config.baseUrl)
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        }
    }