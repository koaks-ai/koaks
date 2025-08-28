package org.koaks.framework.tools

import org.koaks.framework.annotation.Param
import org.koaks.framework.annotation.Tool

class WeatherTools {

    @Tool(
        params = [
            Param(param = "city", description = "city name, like Shanghai", required = true),
            Param(param = "date", description = "date, like 2025-08-17", required = true)
        ],
        group = "weather",
        description = "Get the weather for a specific city today."
    )
    fun getWeather(city: String, date: String): String {
        return "For $city on $date, the weather is cloudy with a high wind warning."
    }

    @Tool(
        group = "location",
        description = "Get the city where the user is located",
        params =  []
    )
    fun getCity(): String {
        return "Shanghai"
    }

}