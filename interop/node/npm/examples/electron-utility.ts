import { createRuntime } from "@koaks/node";

const runtime = createRuntime({ maxConcurrency: 2 });
const agent = await runtime.createAgent({
  id: "utility-assistant",
  model: {
    type: "openai",
    apiKey: process.env.OPENAI_API_KEY!,
    model: "gpt-4.1-mini",
  },
  memory: { type: "window" },
});

process.parentPort.on("message", async ({ data }) => {
  if (data.type === "run") {
    const result = await agent.run(data.input, { threadId: data.threadId });
    process.parentPort.postMessage({ type: "result", requestId: data.requestId, result });
  }
  if (data.type === "close") {
    await agent.close();
    await runtime.close();
    process.exit(0);
  }
});
