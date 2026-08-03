package org.koaks.javaapi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.koaks.framework.annotation.Param;
import org.koaks.framework.annotation.Tool;

public final class AnnotatedToolContainer {
    @Param(name = "weatherInput", description = "city and date")
    public record WeatherInput(
            @Param(name = "city", description = "City name for the weather query") String city,
            @Param(name = "date", description = "Date for the weather query") String date
    ) {}

    final AtomicBoolean weatherRanOnVirtualThread = new AtomicBoolean();
    final AtomicReference<WeatherInput> weatherInput = new AtomicReference<>();
    final AtomicBoolean locationCalled = new AtomicBoolean();
    final AtomicBoolean asyncCalled = new AtomicBoolean();
    final AtomicReference<String> scalarCity = new AtomicReference<>();

    @Tool(name = "get_weather", description = "Get the weather for a city")
    public String getWeather(WeatherInput input) {
        weatherRanOnVirtualThread.set(Thread.currentThread().isVirtual());
        weatherInput.set(input);
        return input.date() + " " + input.city() + ": cloudy";
    }

    @Tool(name = "get_user_location", description = "Get the user's location")
    public String getUserLocation() {
        locationCalled.set(true);
        return "Shanghai";
    }

    @Tool(name = "get_user_date", description = "Get the user's date")
    public CompletionStage<String> getUserDate() {
        asyncCalled.set(true);
        return CompletableFuture.completedFuture("2026-08-03");
    }

    @Tool("Get weather from a direct city parameter")
    public String getCityWeather(
            @Param(name = "city", description = "City name, for example Shanghai") String city
    ) {
        scalarCity.set(city);
        return city + ": cloudy";
    }
}
