# @koaks/node

Node.js 20+ ESM facade for Koaks. Use it from an Electron main process,
Electron `utilityProcess`, or another trusted Node.js process. Do not load this
package in an Electron renderer: model credentials and privileged tools belong
outside the renderer sandbox.

## Local installation

Build and verify the package from the Koaks repository:

```bash
./gradlew :interop:node:checkNodePackage
./gradlew :interop:node:npmPack
```

The tarball is written to `interop/node/build/npm-package/`. Install that file in
the Electron application:

```bash
npm install ../koaks/interop/node/build/npm-package/koaks-node-0.0.1-beta3.tgz
```

The package is ESM-only and requires Node.js 20 or newer.

## Runtime and streaming

```ts
import { createRuntime } from "@koaks/node";

const runtime = createRuntime({ maxConcurrency: 4, highWaterMark: 64 });
const agent = await runtime.createAgent({
  id: "assistant",
  instructions: "Answer concisely.",
  model: {
    type: "openai",
    apiKey: process.env.OPENAI_API_KEY!,
    model: "gpt-4.1-mini",
  },
  memory: { type: "window", maxMessages: 40 },
});

for await (const event of agent.stream("Hello", { threadId: "chat-1" })) {
  if (event.type === "text_delta") process.stdout.write(event.text);
}

await agent.close();
await runtime.close();
```

Streams are single-consumer bounded `AsyncIterable` instances. Stopping a
`for await` loop cancels the underlying Kotlin Flow and agent run.

## JavaScript tools and RuntimeContext

```ts
const controller = new AbortController();

const agent = await runtime.createAgent({
  id: "tools",
  model: { type: "qwen", apiKey: process.env.QWEN_API_KEY!, model: "qwen-plus" },
  tools: [{
    name: "read_record",
    description: "Read one application record",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string" } },
      required: ["id"],
    },
    async execute({ id }, { signal, runtime: toolRuntime }) {
      return await toolRuntime.resources.withResource(`record:${id}`, async () => {
        const response = await fetch(`https://example.test/records/${id}`, { signal });
        const record = await response.json();
        const contextRef = await toolRuntime.context.put([
          { type: "message", role: "user", content: [{ type: "text", text: JSON.stringify(record) }] },
        ]);
        await toolRuntime.ipc.publish("desktop:progress", "record_loaded", id, {
          contextRefs: [contextRef],
        });
        return record;
      });
    },
  }],
});

const result = await agent.run("Read record 42", { signal: controller.signal });
```

Tool arguments are parsed from the model's JSON before `execute` is called. The
Tool RuntimeContext exposes the current run metadata, shared resource locks,
run-scoped ContextStore access, Agent IPC, and true child spawning. If the run
is cancelled, the tool's `AbortSignal` is aborted and pending Context operations
are cancelled.

The Tool RuntimeContext expires when `execute` returns. Context refs and child
`RunHandle` objects created by the callback remain valid. Release saved handles
when the application no longer needs them:

```ts
let researcher: Awaited<ReturnType<typeof runtime.createAgent>>;

const orchestrator = await runtime.createAgent({
  id: "orchestrator",
  model: { type: "openai", apiKey: process.env.OPENAI_API_KEY!, model: "gpt-4.1-mini" },
  tools: [{
    name: "research",
    inputSchema: { type: "object", properties: { query: { type: "string" } }, required: ["query"] },
    async execute({ query }, { runtime: toolRuntime }) {
      const child = await toolRuntime.spawnChild(researcher, query, {
        failurePolicy: "capture",
        conversation: { type: "inherit" },
      });
      try {
        return await child.result();
      } finally {
        await child.release();
      }
    },
  }],
});
```

`inherit` joins the parent Thread and Turn, `ephemeral` disables persistent
conversation state, and `thread` starts an independent Turn. A `propagate`
child failure fails its parent after the child tree settles; `capture` leaves
the result for the caller to consume.

## Runtime IPC operator

Electron Main can act as an external operator without pretending to be an
Agent run. It can send or request messages and subscribe to progress topics:

```ts
const progress = runtime.ipc.subscribe("desktop:progress");
void (async () => {
  for await (const message of progress) {
    mainWindow.webContents.send("agent:progress", message);
  }
})();

const reply = await runtime.ipc.request(activeRunId, "approval", "allow", {
  timeoutMs: 30_000,
  signal: shutdownController.signal,
});
```

Inside a Tool, `ipc.receive()` returns an opaque request message that can be
passed once to `ipc.reply()`. Correlation tokens are kept inside the SDK, so a
caller cannot forge or reuse a reply. IPC is in-memory within one Koaks Runtime;
applications still forward messages themselves when Electron Main and a
`utilityProcess` use different processes.

## Model fallback, custom memory, and MCP

```ts
const agent = await runtime.createAgent({
  id: "resilient",
  model: [
    { type: "anthropic", apiKey: process.env.ANTHROPIC_API_KEY!, model: "claude-sonnet-4-5", maxTokens: 2048 },
    { type: "ollama", baseUrl: "http://127.0.0.1:11434", model: "qwen3:8b" },
  ],
  memory: {
    type: "vector",
    id: "application-vector-store",
    topK: 8,
    store: {
      async add(threadId, items) { /* persist embeddings and items */ },
      async search(threadId, query, topK) { return []; },
    },
  },
  mcp: [
    { type: "http", url: "http://127.0.0.1:3001/mcp", clientId: 1 },
    {
      type: "gateway",
      async listTools() { return []; },
      async callTool(name, argumentsValue) { return { name, argumentsValue }; },
    },
  ],
});
```

Provider arrays are tried in order. MCP compatibility in this release covers
`tools/list` and `tools/call`.

## Electron

Keep the runtime in the main process for a small application. For stronger
fault and memory isolation, run it in an Electron `utilityProcess` and forward
only application-level commands and events over Electron messaging. Complete
examples are included in `examples/electron-main.ts` and
`examples/electron-utility.ts`.

Always close agents and the runtime during application shutdown. `close()` is
idempotent and cancels active streams, handles, tools, HTTP transports, memory,
and MCP resources before returning.
