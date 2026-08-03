package examples;

import org.koaks.framework.loop.AgentResult;
import org.koaks.framework.annotation.Param;
import org.koaks.framework.annotation.Tool;
import org.koaks.java.Agent;
import org.koaks.java.qwen.Qwen;

/**
 * Pure Java 21 quick start for the Koaks JVM artifact.
 */
public final class JavaQuickStart {
    record WeatherInput(
            @Param(name = "city", description = "City name, for example Shanghai") String city
    ) {
    }

    static final class WeatherTools {
        @Tool("Get the weather for a city")
        public String getWeather(WeatherInput input) {
            return input.city() + ": cloudy";
        }
    }

    private JavaQuickStart() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("QWEN_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("QWEN_API_KEY is required");
        }

        try (Agent agent = Agent.builder()
                .id("java-weather-agent")
                .instructions("Answer concisely and call tools when useful.")
                .model(Qwen.builder().apiKey(apiKey).modelName("qwen-plus").build())
                .tool(new WeatherTools())
                .maxSteps(20)
                .build()) {
            AgentResult result = agent.run("What is the weather in Shanghai?");
            System.out.println(result.getText());
        }
    }
}
