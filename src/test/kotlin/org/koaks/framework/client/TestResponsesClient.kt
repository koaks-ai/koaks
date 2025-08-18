package org.koaks.framework.client

import org.junit.jupiter.api.BeforeAll
import org.koaks.framework.EnvTools
import org.koaks.framework.Koaks
import org.koaks.framework.api.dsl.createResponsesClient


class TestResponsesClient {

    companion object {
        @BeforeAll
        @JvmStatic
        fun initKoaks() {
            Koaks.init("org.koaks.framework")
        }

        val client = createResponsesClient {
            model {
                baseUrl = EnvTools.loadValue("BASE_URL")
                apiKey = EnvTools.loadValue("API_KEY")
                modelName = "qwen-plus"
            }
            memory {
                default()
            }
        }
    }


}
