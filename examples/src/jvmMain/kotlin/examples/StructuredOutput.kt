package examples

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.run
import org.koaks.framework.loop.tool
import org.koaks.framework.loop.use
import org.koaks.provider.openai.openai
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun main() = runBlocking {
    val agent = agent {
        id = "weather-structured-output"
        name = "personalized-assistant"
        instructions {
            +"你是一个简洁、乐于助人的助手。"
            +"始终用中文回答。"
        }
        model {
            openai(
                baseUrl = EnvTools.loadValue("BASE_URL"),
                apiKey = EnvTools.loadValue("API_KEY"),
                modelName = "qwen3.7-plus",
            ) {
                temperature = 0.7
            }
        }

        tools {
            tool<NoInput>(
                name = "get_local_city",
                description = "获取当前系统所在的城市",
            ) {
                // In a real agent, this might do an IP geolocation lookup or read from user profile.
                "当前系统所在的城市：西安"
            }

            tool<NoInput>(
                name = "get_local_time",
                description = "获取当前系统所在时区的本地时间",
            ) {
                val zone = ZoneId.of(ZoneId.systemDefault().id)
                val now = ZonedDateTime.now(zone)
                "当前系统所在时区的本地时间：${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}"
            }

            tool<WeatherInput>(
                name = "get_weather",
                description = "获取指定城市的天气信息",
            ) { input ->
                getWeather(input.city)
            }
        }
    }

    agent.use {
        val cityWeather: CityWeather = it.run<CityWeather>("今天天气怎么样？")
        println("${cityWeather.city}: ${cityWeather.tempC}°C")
    }
}

@Serializable
data class CityWeather(val city: String, val tempC: Int)


private fun getWeather(city: String): String = "$city 天气: 晴天，温度28摄氏度，适合出门。"
