package org.endow.framework.test

import org.endow.framework.annotation.Param
import org.endow.framework.annotation.Tool

class TestTools {

    @Tool(
        params = [
            Param(param = "city", description = "CityName", required = true)
        ],
        description = "getTodayWeather"
    )
    fun getWeather(city: String?): String {
        return "Today's weather is cloudy, with a high wind warning."
    }

    @Tool(description = "getUserCity")
    fun getCity(): String {
        return "Taian"
    }

}