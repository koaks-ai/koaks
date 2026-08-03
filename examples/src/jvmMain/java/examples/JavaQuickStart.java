package examples;

import org.koaks.framework.loop.AgentEvent;
import org.koaks.framework.loop.AgentResult;
import org.koaks.framework.annotation.Param;
import org.koaks.framework.annotation.Tool;
import org.koaks.java.Agent;
import org.koaks.java.EventStream;
import org.koaks.java.openai.OpenAI;
import org.koaks.java.qwen.Qwen;

import java.util.Date;

/**
 * Pure Java 21 quick start for the Koaks JVM artifact.
 */
public final class JavaQuickStart {
    public record WeatherInput(
            @Param(name = "city", description = "City name, for example Shanghai")
            String city,
            @Param(name = "date", description = "Date in YYYY-MM-DD format, for example 2026-01-01")
            String date
    ) {
    }

    public static final class WeatherTools {
        @Tool("Get the weather for a city")
        public String getWeather(WeatherInput input) {
            return input.city() + ": cloudy";
        }

        @Tool("Get the location of the user")
        public String getLocation() {
            return "Shanghai, China";
        }

        @Tool("Get the current date")
        public String getDate() {
            return Date.from(new Date().toInstant()).toString();
        }
    }

    private JavaQuickStart() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = EnvTools.loadValue("OPENAI_BASE_URL");
        String apiKey = EnvTools.loadValue("OPENAI_API_KEY");

        try (Agent agent = Agent.builder()
                .id("java-weather-agent")
                .instructions("You are a helpful assistant.")
                .model(OpenAI.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName("deepseek-ai/DeepSeek-V4-Flash")
                        .build()
                )
                .tool(new WeatherTools())
                .maxSteps(20)
                .build()) {
            try (EventStream stream = agent.stream("今天上海天气怎么样？")) {
                stream.forEach(event -> {
                    switch (event) {
                        case AgentEvent.TextDelta delta -> {
                            System.out.print(delta.getText());
                            System.out.flush();
                        }
                        case AgentEvent.ToolCallRequested requested -> {
                            var call = requested.getCall();
                            System.out.printf(
                                    "%n[调用工具] %s %s%n",
                                    call.getName(),
                                    call.getArguments()
                            );
                        }
                        case AgentEvent.ToolResult result -> System.out.printf(
                                "[工具结果] %s%n",
                                result.getOutput()
                        );
                        case AgentEvent.Failed failed -> System.err.println(
                                "[执行错误] " + failed.getError().getMessage()
                        );
                        case AgentEvent.Completed completed ->
                                System.out.printf(
                                        "%n[完成] usage=%s%n",
                                        completed.getUsage()
                                );
                        case AgentEvent.Terminated terminated -> System.out.printf(
                                "%n[提前终止] reason=%s%n",
                                terminated.getReason()
                        );
                        default -> {
                        }
                    }
                }).join();
            }
        }
    }
}
