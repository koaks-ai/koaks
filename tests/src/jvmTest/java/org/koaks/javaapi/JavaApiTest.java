package org.koaks.javaapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.koaks.framework.annotation.Tool;
import org.koaks.framework.loop.AgentEvent;
import org.koaks.framework.loop.AgentResult;
import org.koaks.java.Agent;
import org.koaks.java.AgentRuntime;
import org.koaks.java.EventStream;
import org.koaks.java.Models;
import org.koaks.java.RunOptions;
import org.koaks.java.StructuredOutputException;
import org.koaks.java.anthropic.Anthropic;
import org.koaks.java.json.JacksonType;
import org.koaks.java.ollama.Ollama;
import org.koaks.java.openai.OpenAI;
import org.koaks.java.qwen.Qwen;
import org.koaks.java.tool.Tools;
import tools.jackson.core.type.TypeReference;

class JavaApiTest {
    record WeatherInput(String city) {}
    record Weather(String city, int tempC) {}

    @Test
    void blockingAndAsyncRunsUsePlainJavaSignatures() throws Exception {
        try (var blocking = Agent.builder()
                .id("java-blocking")
                .model(Models.custom(JavaFacadeFixtures.textModel("hello")))
                .build()) {
            AgentResult result = blocking.run("hi");
            assertEquals("hello", result.getText());
        }

        try (var async = Agent.builder()
                .id("java-async")
                .model(Models.custom(JavaFacadeFixtures.textModel("async")))
                .build()) {
            assertEquals("async", async.runAsync("hi").get(5, TimeUnit.SECONDS).getText());
        }
    }

    @Test
    void blockingRunCanBeCalledFromVirtualThread() throws Exception {
        try (var agent = Agent.builder()
                .id("java-virtual-run")
                .model(Models.custom(JavaFacadeFixtures.textModel("virtual")))
                .build()) {
            var text = new CompletableFuture<String>();
            Thread.startVirtualThread(() -> {
                try {
                    assertTrue(Thread.currentThread().isVirtual());
                    text.complete(agent.run("hi").getText());
                } catch (Throwable failure) {
                    text.completeExceptionally(failure);
                }
            });
            assertEquals("virtual", text.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void structuredOutputUsesJacksonRecords() throws Exception {
        try (var agent = Agent.builder()
                .id("java-structured")
                .model(Models.custom(JavaFacadeFixtures.structuredModel("{\"city\":\"Shanghai\",\"tempC\":28}")))
                .build()) {
            Weather weather = agent.runStructured("weather", Weather.class);
            assertEquals("Shanghai", weather.city());
            assertEquals(28, weather.tempC());
        }
    }

    @Test
    void jacksonTypeSupportsSchemasGenericsAndDecodeFailures() {
        JacksonType<Weather> weatherType = JacksonType.of(Weather.class);
        assertTrue(weatherType.schemaJson().contains("required"));
        assertTrue(weatherType.schemaJson().contains("city"));

        JacksonType<List<Weather>> listType = JacksonType.of(new TypeReference<List<Weather>>() {});
        List<Weather> values = listType.decode("[{\"city\":\"Shanghai\",\"tempC\":28}]");
        assertEquals("Shanghai", values.getFirst().city());

        try (var agent = Agent.builder()
                .id("java-invalid-structured")
                .model(Models.custom(JavaFacadeFixtures.structuredModel("not-json")))
                .build()) {
            assertThrows(StructuredOutputException.class,
                    () -> agent.runStructured("weather", Weather.class));
        }
    }

    @Test
    void blockingToolRunsOnVirtualThread() throws Exception {
        var virtual = new AtomicBoolean();
        try (var agent = Agent.builder()
                .id("java-tool")
                .model(Models.custom(JavaFacadeFixtures.toolModel(
                        "get_weather", "{\"city\":\"Shanghai\"}", "done")))
                .tool(Tools.sync("get_weather", "weather", WeatherInput.class, input -> {
                    virtual.set(Thread.currentThread().isVirtual());
                    return input.city() + ": cloudy";
                }))
                .build()) {
            assertEquals("done", agent.run("weather").getText());
            assertTrue(virtual.get());
        }
    }

    @Test
    void asyncToolAcceptsCompletionStage() throws Exception {
        try (var agent = Agent.builder()
                .id("java-async-tool")
                .model(Models.custom(JavaFacadeFixtures.toolModel(
                        "get_weather", "{\"city\":\"Shanghai\"}", "done")))
                .tool(Tools.async(
                        "get_weather",
                        "weather",
                        WeatherInput.class,
                        input -> CompletableFuture.completedFuture(input.city() + ": cloudy")))
                .build()) {
            assertEquals("done", agent.run("weather").getText());
        }
    }

    @Test
    void annotatedToolContainerScansAllPublicToolMethods() throws Exception {
        var tools = new AnnotatedToolContainer();
        try (var weatherAgent = Agent.builder()
                .id("java-annotated-weather")
                .model(Models.custom(JavaFacadeFixtures.toolModel(
                        "get_weather",
                        "{\"city\":\"Shanghai\",\"date\":\"2026-08-03\"}",
                        "done")))
                .tool(tools)
                .build()) {
            assertEquals("done", weatherAgent.run("weather").getText());
            assertEquals("Shanghai", tools.weatherInput.get().city());
            assertTrue(tools.weatherRanOnVirtualThread.get());
        }

        try (var locationAgent = Agent.builder()
                .id("java-annotated-location")
                .model(Models.custom(JavaFacadeFixtures.toolModel(
                        "get_user_location", "{}", "done")))
                .tool(tools)
                .build()) {
            assertEquals("done", locationAgent.run("location").getText());
            assertTrue(tools.locationCalled.get());
        }

        try (var dateAgent = Agent.builder()
                .id("java-annotated-date")
                .model(Models.custom(JavaFacadeFixtures.toolModel(
                        "get_user_date", "{}", "done")))
                .tool(tools)
                .build()) {
            assertEquals("done", dateAgent.run("date").getText());
            assertTrue(tools.asyncCalled.get());
        }

        try (var scalarAgent = Agent.builder()
                .id("java-annotated-scalar")
                .model(Models.custom(JavaFacadeFixtures.describedToolModel(
                        "getCityWeather",
                        "{\"city\":\"Shanghai\"}",
                        "city",
                        "City name, for example Shanghai",
                        "done")))
                .tool(tools)
                .build()) {
            assertEquals("done", scalarAgent.run("weather").getText());
            assertEquals("Shanghai", tools.scalarCity.get());
        }

        String schema = JacksonType.of(AnnotatedToolContainer.WeatherInput.class).schemaJson();
        assertTrue(schema.contains("city and date"));
        assertTrue(schema.contains("City name for the weather query"));
        assertTrue(schema.contains("Date for the weather query"));
    }

    @Test
    void annotatedToolContainerRejectsInvalidMethodsAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> Agent.builder()
                .id("java-invalid-annotated-tool")
                .model(Models.custom(JavaFacadeFixtures.textModel("unused")))
                .tool(new InvalidAnnotatedTools())
                .build());

    }

    static final class InvalidAnnotatedTools {
        @Tool(name = "invalid", description = "not public")
        private String invalid() {
            return "invalid";
        }
    }

    @Test
    void eventStreamPublishesAgentEvents() throws Exception {
        try (var agent = Agent.builder()
                .id("java-stream")
                .model(Models.custom(JavaFacadeFixtures.textModel("streamed")))
                .build()) {
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            try (EventStream stream = agent.stream("hi")) {
                stream.forEach(events::add).get(5, TimeUnit.SECONDS);
            }
            assertTrue(events.stream().anyMatch(AgentEvent.TextDelta.class::isInstance));
            assertTrue(events.stream().anyMatch(AgentEvent.Completed.class::isInstance));
        }
    }

    @Test
    void eventStreamHonorsDemandAndRejectsSecondSubscriber() throws Exception {
        try (var agent = Agent.builder()
                .id("java-stream-demand")
                .model(Models.custom(JavaFacadeFixtures.textModel("streamed")))
                .build();
             EventStream stream = agent.stream("hi")) {
            var subscription = new AtomicReference<Flow.Subscription>();
            var count = new AtomicInteger();
            var first = new CountDownLatch(1);
            var completed = new CountDownLatch(1);

            stream.subscribe(new Flow.Subscriber<AgentEvent>() {
                @Override public void onSubscribe(Flow.Subscription value) {
                    subscription.set(value);
                    value.request(1);
                }
                @Override public void onNext(AgentEvent item) {
                    count.incrementAndGet();
                    first.countDown();
                }
                @Override public void onError(Throwable throwable) { completed.countDown(); }
                @Override public void onComplete() { completed.countDown(); }
            });

            assertTrue(first.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(1, count.get());
            subscription.get().request(Long.MAX_VALUE);
            assertTrue(completed.await(5, TimeUnit.SECONDS));

            var rejected = new AtomicBoolean();
            stream.subscribe(new Flow.Subscriber<AgentEvent>() {
                @Override public void onSubscribe(Flow.Subscription value) { value.request(1); }
                @Override public void onNext(AgentEvent item) {}
                @Override public void onError(Throwable throwable) { rejected.set(true); }
                @Override public void onComplete() {}
            });
            assertTrue(rejected.get());
        }
    }

    @Test
    void cancellingFutureCancelsRunHandle() {
        try (var agent = Agent.builder()
                .id("java-cancel")
                .model(Models.custom(JavaFacadeFixtures.neverCompletingModel()))
                .build()) {
            var handle = agent.spawn("wait");
            var result = handle.resultAsync();
            assertTrue(result.cancel(true));
            assertTrue(result.isCancelled());
            assertFalse(handle.isActive());
        }
    }

    @Test
    void explicitRuntimeUsesRunOptions() throws Exception {
        try (var runtime = AgentRuntime.builder().maxConcurrency(2).defaultMemoryWindow(8).build();
             var agent = Agent.builder()
                     .id("java-runtime")
                     .model(Models.custom(JavaFacadeFixtures.textModel("managed")))
                     .build()) {
            var options = RunOptions.builder().threadId("thread-1").priority(3).maxSteps(10).build();
            assertEquals("managed", runtime.run(agent, "hi", options).getText());
        }
    }

    @Test
    void providerBuildersValidateRequiredFieldsAndComposeFallbacks() {
        assertThrows(IllegalArgumentException.class, () -> Qwen.builder().modelName("qwen3").build());

        var primary = Qwen.builder().apiKey("test").modelName("qwen3").build();
        var fallback = OpenAI.builder().apiKey("test").modelName("gpt-test").build();
        primary.fallback(fallback);

        Ollama.builder().baseUrl("http://localhost:11434").modelName("llama3").build();
        Anthropic.builder().apiKey("test").modelName("claude-test").build();
    }
}
