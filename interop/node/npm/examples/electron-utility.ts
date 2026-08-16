import { createRuntime, type RunHandle } from "@koaks/node";

type ActiveRun = { handle: RunHandle; done: Promise<void> };

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

let acceptingRuns = true;
const activeRuns = new Map<string, ActiveRun>();

async function startRun(requestId: string, input: string, threadId: string): Promise<void> {
  if (!acceptingRuns) throw new Error("Utility process is shutting down");
  if (activeRuns.has(requestId)) throw new Error(`Duplicate requestId: ${requestId}`);

  const handle = await agent.spawn(input, { threadId, correlationId: requestId });
  const active: ActiveRun = { handle, done: Promise.resolve() };
  activeRuns.set(requestId, active);
  active.done = (async () => {
    try {
      for await (const envelope of handle.events()) {
        process.parentPort.postMessage({ type: "event", requestId, envelope });
      }
      process.parentPort.postMessage({ type: "result", requestId, result: await handle.result() });
    } catch (error) {
      process.parentPort.postMessage({
        type: "error",
        requestId,
        message: error instanceof Error ? error.message : String(error),
      });
    } finally {
      if (activeRuns.get(requestId) === active) activeRuns.delete(requestId);
      await handle.release();
    }
  })();
  await active.done;
}

async function close(): Promise<void> {
  acceptingRuns = false;
  const runs = [...activeRuns.values()];
  await Promise.allSettled(runs.map(({ handle }) => handle.cancel("Utility process shutdown")));
  await Promise.allSettled(runs.map(({ done }) => done));
  await agent.close();
  await runtime.close();
}

process.parentPort.on("message", ({ data }) => {
  if (data.type === "run") {
    void startRun(data.requestId, data.input, data.threadId).catch((error) => {
      process.parentPort.postMessage({
        type: "error",
        requestId: data.requestId,
        message: error instanceof Error ? error.message : String(error),
      });
    });
  } else if (data.type === "cancel") {
    void activeRuns.get(data.requestId)?.handle.cancel("Cancelled by parent process");
  } else if (data.type === "close") {
    void close().finally(() => process.exit(0));
  }
});
