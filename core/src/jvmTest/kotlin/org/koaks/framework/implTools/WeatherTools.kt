package org.koaks.framework.implTools

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.koaks.framework.annotation.Description
import org.koaks.framework.toolcall.Tool

class WeatherImplTools : Tool<WeatherInput> {

    override val name: String = "getWeather"
    override val description: String = "get the weather for a specific city today."
    override val group: String = "weather"
    override val serializer: KSerializer<WeatherInput> = WeatherInput.serializer()

    override suspend fun execute(input: WeatherInput): String {
        return "For ${input.city} on ${input.date}, the weather is cloudy with a high wind warning."
    }

}

@Serializable
class WeatherInput(
    @Description("city name, like Shanghai")
    val city: String,
    @Description("date, like 2025-08-17")
    val date: String
)
