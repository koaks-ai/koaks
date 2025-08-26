package org.koaks.provider.qwen.client

import org.junit.jupiter.api.BeforeAll
import org.koaks.provider.qwen.EnvTools
import org.koaks.framework.Koaks
import org.koaks.framework.api.dsl.createResponsesClient
import org.koaks.provider.qwen.qwen


class TestResponsesClient {

    companion object {
        @BeforeAll
        @JvmStatic
        fun initKoaks() {
            Koaks.init("org.koaks.framework")
        }

        val client = createResponsesClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen3-235b-a22b-instruct-2507",
                )
            }
            tools {

            }
        }
    }


}
