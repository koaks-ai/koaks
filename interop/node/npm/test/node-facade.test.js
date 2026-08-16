import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, test } from "node:test";
import { createRuntime } from "../dist/index.js";

let server;
let baseUrl;
const requests = [];

function sendChunk(response, chunk) {
  response.write(`data: ${JSON.stringify(chunk)}\n\n`);
}

before(async () => {
  server = createServer(async (request, response) => {
    let body = "";
    for await (const chunk of request) body += chunk;
    const payload = JSON.parse(body);
    requests.push(payload);
    if (request.url === "/mcp") {
      response.writeHead(200, { "content-type": "application/json" });
      if (payload.method === "initialize") {
        response.end(JSON.stringify({ jsonrpc: "2.0", id: payload.id, result: null, error: null }));
      } else if (payload.method === "notifications/initialized") {
        response.end("{}");
      } else if (payload.method === "tools/list") {
        response.end(JSON.stringify({
          jsonrpc: "2.0",
          id: payload.id,
          result: { tools: [{ name: "mcp_echo", description: "Echo", inputSchema: { type: "object" } }] },
        }));
      } else if (payload.method === "tools/call") {
        response.end(JSON.stringify({ jsonrpc: "2.0", id: payload.id, result: { result: 33 } }));
      } else {
        response.statusCode = 400;
        response.end(JSON.stringify({ error: "unknown MCP method" }));
      }
      return;
    }
    const last = payload.messages.at(-1);
    const firstUser = payload.messages.find((message) => message.role === "user")?.content;
    const toolResults = payload.messages.filter((message) => message.role === "tool");
    if (last?.content === "model error") {
      response.writeHead(200, { "content-type": "text/event-stream" });
      sendChunk(response, { id: "model-error", error: { message: "fixture model error", type: "fixture" } });
      response.write("data: [DONE]\n\n");
      response.end();
      return;
    }
    response.writeHead(200, { "content-type": "text/event-stream" });
    if (last?.content === "parallel tools") {
      sendChunk(response, {
        id: "parallel-tool-calls",
        choices: [{
          index: 0,
          delta: {
            tool_calls: [
              {
                index: 0,
                id: "parallel-call-1",
                type: "function",
                function: { name: "parallel_tool", arguments: "{\"value\":1}" },
              },
              {
                index: 1,
                id: "parallel-call-2",
                type: "function",
                function: { name: "parallel_tool", arguments: "{\"value\":2}" },
              },
            ],
          },
        }],
      });
    } else if (firstUser === "saved handle" && last?.role === "tool" && toolResults.length === 1) {
      sendChunk(response, {
        id: "saved-handle-second-tool",
        choices: [{
          index: 0,
          delta: {
            tool_calls: [{
              index: 0,
              id: "native-call-2",
              type: "function",
              function: { name: "await_saved", arguments: "{}" },
            }],
          },
        }],
      });
    } else if (payload.response_format?.type === "json_schema") {
      sendChunk(response, {
        id: "structured-response",
        choices: [{ index: 0, delta: { content: "{\"answer\":42}" } }],
        usage: { prompt_tokens: 3, completion_tokens: 3, total_tokens: 6 },
      });
    } else if (last?.role === "tool") {
      sendChunk(response, {
        id: "after-tool",
        choices: [{ index: 0, delta: { content: `tool=${last.content}` } }],
        usage: { prompt_tokens: 4, completion_tokens: 2, total_tokens: 6 },
      });
    } else if (typeof last?.content === "string" && new Set([
      "call tool",
      "abort tool",
      "fail tool",
      "call mcp",
      "increment",
      "runtime context",
      "resource failure",
      "spawn child",
      "ipc child",
      "ipc silent",
      "ipc operator",
      "ipc timeout",
      "ipc cancel",
      "ipc receive cancel",
      "saved handle",
      "conversation modes",
      "capture failure",
      "propagate failure",
      "cross runtime",
      "publish progress",
      "subscribe progress",
      "tool progress",
    ]).has(last.content)) {
      const toolNames = {
        "call tool": "double",
        "abort tool": "wait",
        "fail tool": "explode",
        "call mcp": "mcp_echo",
        increment: "increment",
        "runtime context": "runtime_context",
        "resource failure": "resource_failure",
        "spawn child": "spawn_child",
        "ipc child": "ipc_child",
        "ipc silent": "ipc_silent",
        "ipc operator": "ipc_operator",
        "ipc timeout": "ipc_timeout",
        "ipc cancel": "ipc_cancel",
        "ipc receive cancel": "ipc_receive_cancel",
        "saved handle": "save_child",
        "conversation modes": "conversation_modes",
        "capture failure": "capture_failure",
        "propagate failure": "propagate_failure",
        "cross runtime": "cross_runtime",
        "publish progress": "publish_progress",
        "subscribe progress": "subscribe_progress",
        "tool progress": "progress_tool",
      };
      sendChunk(response, {
        id: "tool-call",
        choices: [{
          index: 0,
          delta: {
            tool_calls: [{
              index: 0,
              id: "native-call-1",
              type: "function",
              function: {
                name: toolNames[last.content],
                arguments: "{\"value\":21}",
              },
            }],
          },
        }],
      });
    } else {
      if (last?.content === "slow child") {
        await new Promise((resolve) => setTimeout(resolve, 40));
      }
      const text = `reply:${last?.content ?? ""}`;
      const split = last?.content === "second" || last?.content === "slow stream" ? 3 : text.length;
      for (let offset = 0; offset < text.length; offset += split) {
        sendChunk(response, {
          id: "text-response",
          choices: [{ index: 0, delta: { content: text.slice(offset, offset + split) } }],
          usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 },
        });
        if (last?.content === "slow stream" && offset === 0) {
          await new Promise((resolve) => setTimeout(resolve, 50));
        }
      }
    }
    response.write("data: [DONE]\n\n");
    response.end();
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  baseUrl = `http://127.0.0.1:${address.port}`;
});

after(async () => {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
});

test("ESM facade runs, streams, reuses thread memory, and closes idempotently", async () => {
  const runtime = createRuntime({ maxConcurrency: 2 });
  const agent = await runtime.createAgent({
    id: "assistant",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    memory: { type: "window", maxMessages: 20 },
  });

  const first = await agent.run("first", { threadId: "thread-1" });
  assert.equal(first.status, "completed");
  assert.equal(first.text, "reply:first");

  let streamed = "";
  for await (const event of agent.stream("second", { threadId: "thread-1", highWaterMark: 1 })) {
    if (event.type === "text_delta") streamed += event.text;
  }
  assert.equal(streamed, "reply:second");
  const secondRequest = requests.find((entry) => entry.messages.at(-1)?.content === "second");
  assert.ok(secondRequest.messages.some((message) => message.role === "assistant" && message.content === "reply:first"));

  const ref = await runtime.putContext([{ type: "message", role: "user", content: [{ type: "text", text: "context" }] }]);
  const resolved = await runtime.resolveContext(ref);
  assert.equal(resolved[0].type, "message");
  assert.equal(resolved[0].content[0].text, "context");
  const delta = await runtime.deltaContext(ref, [
    { type: "message", role: "assistant", content: [{ type: "text", text: "delta" }] },
  ]);
  assert.equal((await runtime.resolveContext(delta)).length, 2);

  const structured = await agent.runStructured("Return an answer", {
    name: "answer",
    schema: {
      type: "object",
      properties: { answer: { type: "number" } },
      required: ["answer"],
    },
  });
  assert.deepEqual(structured.output, { answer: 42 });

  const handle = await agent.spawn("handle");
  let handleSnapshot;
  for await (const update of handle.updates({ highWaterMark: 1 })) {
    handleSnapshot = update;
    if (["finished", "failed", "cancelled"].includes(update.state)) break;
  }
  assert.equal((await handle.result()).status, "completed");
  assert.equal(handleSnapshot.runId, handle.runId);

  const graph = await runtime.submit([
    { id: "first", agent, input: "task one" },
    {
      id: "second",
      agent,
      dependsOn: ["first"],
      input: async ({ first: dependency }) => `task after ${dependency.text}`,
    },
  ]);
  assert.equal(graph.first.status, "completed");
  assert.equal(graph.second.status, "completed");

  const supervised = await runtime.spawnSupervised(agent, "supervised", { maxRetries: 1 });
  assert.equal((await supervised.result()).status, "completed");
  const metrics = await runtime.metrics();
  assert.ok(metrics.finished >= 1);
  assert.ok((await runtime.runs()).length >= 1);
  assert.equal((await runtime.snapshot(handle.runId)).runId, handle.runId);
  assert.equal((await runtime.threadSnapshot("thread-1")).id, "thread-1");
  assert.ok((await runtime.reap()) >= 1);

  const replacement = await runtime.replaceAgent({
    id: "assistant",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
  });
  assert.equal((await replacement.run("replacement")).status, "completed");
  await replacement.close();
  await replacement.close();
  await runtime.close();
  await runtime.close();
});

test("spawn handle exposes ordered lifecycle and agent events", async () => {
  const runtime = createRuntime({ runEventBufferCapacity: 64 });
  const agent = await runtime.createAgent({
    id: "handle-events",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
  });
  const handle = await agent.spawn("timeline", { correlationId: "app-run-9" });
  assert.throws(
    () => handle.events({ afterSequence: -1 }),
    (error) => error?.code === "configuration_error",
  );
  const events = [];
  for await (const event of handle.events()) events.push(event);

  assert.equal((await handle.result()).text, "reply:timeline");
  assert.ok(events.some((event) => event.kind === "agent" && event.event.type === "text_delta"));
  assert.ok(events.some((event) => event.kind === "lifecycle" && event.event.type === "finished"));
  assert.deepEqual(events.map((event) => event.sequence), events.map((event) => event.sequence).toSorted((a, b) => a - b));
  assert.ok(events.every((event) => event.correlationId === "app-run-9"));
  await handle.release();
  await runtime.close();
});

test("JS tools receive call identity and report correlated progress", async () => {
  const runtime = createRuntime();
  let toolContext;
  const agent = await runtime.createAgent({
    id: "tool-progress",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [{
      name: "progress_tool",
      inputSchema: { type: "object" },
      execute: async (_arguments, context) => {
        toolContext = context;
        await context.reportProgress({ type: "output", text: "working", stream: "stdout" });
        await context.reportProgress({ type: "status", message: "almost done" });
        return "ok";
      },
    }],
  });
  const events = [];
  for await (const event of agent.stream("tool progress", { correlationId: "progress-run" })) events.push(event);

  const requested = events.find((event) => event.type === "tool_call_requested");
  const progress = events.filter((event) => event.type === "tool_progress");
  assert.equal(toolContext.callId, requested.call.id);
  assert.equal(toolContext.toolName, "progress_tool");
  assert.equal(toolContext.correlationId, "progress-run");
  assert.equal(toolContext.runtime.correlationId, "progress-run");
  assert.equal(progress.length, 2);
  assert.ok(progress.every((event) => event.callId === requested.call.id));
  await runtime.close();
});

test("parallel same-name tools retain distinct call and execution identities", async () => {
  const runtime = createRuntime();
  const contexts = [];
  const agent = await runtime.createAgent({
    id: "parallel-tool-identities",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [{
      name: "parallel_tool",
      inputSchema: { type: "object" },
      execute: async ({ value }, context) => {
        contexts.push(context);
        await context.reportProgress({ type: "status", message: `running ${value}` });
        return String(value);
      },
    }],
  });
  const events = [];
  for await (const event of agent.stream("parallel tools")) events.push(event);

  assert.equal(contexts.length, 2);
  assert.equal(new Set(contexts.map((context) => context.callId)).size, 2);
  assert.equal(new Set(contexts.map((context) => context.executionId)).size, 2);
  const progressCallIds = new Set(events.filter((event) => event.type === "tool_progress").map((event) => event.callId));
  assert.deepEqual(progressCallIds, new Set(contexts.map((context) => context.callId)));
  await runtime.close();
});

test("stopping agent.stream cancels its active Handle without execution backpressure", async () => {
  const runtime = createRuntime();
  const agent = await runtime.createAgent({
    id: "stream-owner",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
  });

  for await (const event of agent.stream("slow stream")) {
    if (event.type === "text_delta") break;
  }

  assert.equal((await runtime.runs()).at(-1).state, "cancelled");
  await runtime.close();
});

test("summarizing memory keeps raw turns and persists only its projection checkpoint", async () => {
  const runtime = createRuntime();
  const rawTurns = [];
  let summaryState;
  const compactionEvents = [];
  const memory = {
    type: "summarizing",
    id: "durable-summary",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    maxTokens: 1,
    keepRecentTurns: 1,
    delegate: {
      type: "custom",
      id: "raw-history",
      open: async () => ({
        load: async () => ({ transcript: rawTurns.flatMap((turn) => turn.items) }),
        commit: async (turn) => { rawTurns.push(turn); },
      }),
    },
    stateStore: {
      load: async () => summaryState,
      save: async (_threadId, checkpoint) => { summaryState = checkpoint; },
      delete: async () => { summaryState = undefined; },
    },
    onCompaction: (event) => { compactionEvents.push(event); },
  };
  const agent = await runtime.createAgent({
    id: "durable-summary-agent",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    memory,
  });

  await agent.run("memory one", { threadId: "summary-thread" });
  await agent.run("memory two", { threadId: "summary-thread" });

  assert.equal(rawTurns.length, 2);
  assert.equal(typeof summaryState.basis.digest, "string");
  assert.equal(summaryState.summary.role, "system");
  assert.ok(compactionEvents.some((event) => event.type === "started"));
  assert.ok(compactionEvents.some((event) => event.type === "completed"));
  await runtime.close();
});

test("summarizing state Store errors retain their JS type and do not duplicate raw turns", async () => {
  class SummaryStoreError extends Error {
    constructor(message) {
      super(message);
      this.name = "SummaryStoreError";
    }
  }

  const runtime = createRuntime();
  const rawTurns = [];
  const agent = await runtime.createAgent({
    id: "summary-store-error",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    memory: {
      type: "summarizing",
      id: "failing-summary-store",
      model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
      maxTokens: 1,
      keepRecentTurns: 1,
      delegate: {
        type: "custom",
        id: "raw-before-summary-failure",
        open: async () => ({
          load: async () => ({ transcript: rawTurns.flatMap((turn) => turn.items) }),
          commit: async (turn) => { rawTurns.push(turn); },
        }),
      },
      stateStore: {
        load: async () => undefined,
        save: async () => { throw new SummaryStoreError("summary store unavailable"); },
        delete: async () => {},
      },
    },
  });

  await agent.run("summary error one", { threadId: "summary-error-thread" });
  await assert.rejects(
    agent.run("summary error two", { threadId: "summary-error-thread" }),
    (error) => error?.code === "SummaryStoreError" && /summary store unavailable/.test(error.message),
  );
  assert.equal(rawTurns.length, 2);
  await runtime.close();
});

test("pending Tool Hooks enter waiting and receive an aborted signal on run cancellation", async () => {
  const runtime = createRuntime();
  let hookStartedResolve;
  const hookStarted = new Promise((resolve) => { hookStartedResolve = resolve; });
  let hookExecution;
  const agent = await runtime.createAgent({
    id: "hook-cancellation",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    mcp: [{
      type: "gateway",
      listTools: async () => [{ name: "mcp_echo", description: "Echo", inputSchema: { type: "object" } }],
      callTool: async () => "unused",
    }],
    hooks: [{
      beforeTool: async (_context, execution) => {
        hookExecution = execution;
        hookStartedResolve();
        return await new Promise((resolve) => {
          execution.signal.addEventListener("abort", () => resolve({ action: "proceed" }), { once: true });
        });
      },
    }],
  });
  const handle = await agent.spawn("call mcp", { correlationId: "cancel-hook" });
  await hookStarted;

  assert.equal((await handle.snapshot()).state, "waiting");
  await handle.cancel("approval dismissed");
  await assert.rejects(handle.result(), (error) => error?.code === "cancelled");
  assert.equal(hookExecution.signal.aborted, true);
  await handle.release();
  await runtime.close();
});

test("JS tools receive parsed arguments and cancellation through AbortSignal", async () => {
  const runtime = createRuntime();
  let toolStartedResolve;
  const toolStarted = new Promise((resolve) => { toolStartedResolve = resolve; });
  let toolAborted = false;
  const agent = await runtime.createAgent({
    id: "tools",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [
      {
        name: "double",
        inputSchema: { type: "object", properties: { value: { type: "number" } }, required: ["value"] },
        execute: ({ value }) => String(value * 2),
      },
      {
        name: "wait",
        inputSchema: { type: "object", properties: { value: { type: "number" } } },
        execute: (_arguments, { signal }) => new Promise((resolve, reject) => {
          toolStartedResolve();
          signal.addEventListener("abort", () => {
            toolAborted = true;
            reject(new Error("tool aborted"));
          }, { once: true });
        }),
      },
      {
        name: "explode",
        inputSchema: { type: "object" },
        execute: async () => { throw new Error("callback rejected"); },
      },
    ],
  });

  const completed = await agent.run("call tool");
  assert.equal(completed.status, "completed");
  assert.equal(completed.text, "tool=42");
  let rejectedToolEvent;
  let recoveredText = "";
  for await (const event of agent.stream("fail tool")) {
    if (event.type === "tool_result" && event.isError) rejectedToolEvent = event;
    if (event.type === "text_delta") recoveredText += event.text;
  }
  assert.match(rejectedToolEvent.output, /callback rejected/);
  assert.match(recoveredText, /callback rejected/);

  const controller = new AbortController();
  const running = agent.run("abort tool", { signal: controller.signal });
  await toolStarted;
  await assert.rejects(
    runtime.replaceAgent({
      id: "tools",
      model: { type: "openai", apiKey: "test", model: "replacement", baseUrl },
    }),
    (error) => error?.code === "lifecycle_error",
  );
  controller.abort("test cancellation");
  await assert.rejects(running, (error) => error?.code === "cancelled" || /cancel/i.test(error?.message));
  assert.equal(toolAborted, true);

  await runtime.close();
});

test("Tool RuntimeContext serializes resources, scopes private context, and expires after callback", async () => {
  const runtime = createRuntime({ maxConcurrency: 2 });
  let counter = 0;
  let privateRef;
  let capturedRuntime;
  const agent = await runtime.createAgent({
    id: "runtime-context",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [
      {
        name: "increment",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          await toolRuntime.resources.withResource("shared-counter", async () => {
            const before = counter;
            await new Promise((resolve) => setTimeout(resolve, 20));
            counter = before + 1;
          });
          return String(counter);
        },
      },
      {
        name: "runtime_context",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          capturedRuntime = toolRuntime;
          assert.ok(toolRuntime.runId);
          assert.equal(toolRuntime.agentId, "runtime-context");
          privateRef = await toolRuntime.context.put([
            { type: "message", role: "user", content: [{ type: "text", text: "private" }] },
          ]);
          const resolved = await toolRuntime.context.resolve(privateRef);
          return resolved[0].content[0].text;
        },
      },
      {
        name: "resource_failure",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          await toolRuntime.resources.withResource("shared-counter", async () => {
            throw new Error("critical section failed");
          });
        },
      },
    ],
  });

  const increments = await Promise.all([agent.run("increment"), agent.run("increment")]);
  assert.ok(increments.every((result) => result.status === "completed"));
  assert.equal(counter, 2);

  assert.equal((await agent.run("resource failure")).status, "completed");
  assert.equal((await agent.run("increment")).status, "completed");
  assert.equal(counter, 3);

  assert.equal((await agent.run("runtime context")).status, "completed");
  await assert.rejects(runtime.resolveContext(privateRef), (error) => error?.code === "context_access");
  await assert.rejects(
    capturedRuntime.context.resolve(privateRef),
    (error) => error?.code === "tool_context_expired",
  );
  await runtime.close();
});

test("child Agents preserve parent semantics and saved handles remain usable across Tools", async () => {
  const runtime = createRuntime({ maxConcurrency: 1 });
  const foreignRuntime = createRuntime();
  let savedHandle;
  let spawnedHandle;
  let conversationHandles;
  const child = await runtime.createAgent({
    id: "child",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
  });
  const foreignChild = await foreignRuntime.createAgent({
    id: "foreign-child",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
  });
  const parent = await runtime.createAgent({
    id: "parent",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [
      {
        name: "spawn_child",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const contextRef = await toolRuntime.context.put([
            { type: "message", role: "user", content: [{ type: "text", text: "child context" }] },
          ]);
          spawnedHandle = await toolRuntime.spawnChild(child, "child input", {
            failurePolicy: "capture",
            conversation: { type: "ephemeral" },
            contextRefs: [contextRef],
          });
          const result = await spawnedHandle.result();
          return result.text;
        },
      },
      {
        name: "save_child",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          savedHandle = await toolRuntime.spawnChild(child, "slow child", {
            failurePolicy: "capture",
            conversation: { type: "ephemeral" },
          });
          return "saved";
        },
      },
      {
        name: "await_saved",
        inputSchema: { type: "object" },
        execute: async () => (await savedHandle.result()).text,
      },
      {
        name: "conversation_modes",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const inherit = await toolRuntime.spawnChild(child, "inherit child", {
            failurePolicy: "capture",
            conversation: { type: "inherit" },
          });
          await inherit.result();
          const ephemeral = await toolRuntime.spawnChild(child, "ephemeral child", {
            failurePolicy: "capture",
            conversation: { type: "ephemeral" },
          });
          await ephemeral.result();
          const threaded = await toolRuntime.spawnChild(child, "thread child", {
            failurePolicy: "capture",
            conversation: { type: "thread", threadId: "child-thread" },
          });
          await threaded.result();
          conversationHandles = { parent: toolRuntime, inherit, ephemeral, threaded };
          return "modes complete";
        },
      },
      {
        name: "capture_failure",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const handle = await toolRuntime.spawnChild(child, "model error", {
            failurePolicy: "capture",
            conversation: { type: "ephemeral" },
          });
          return (await handle.result()).status;
        },
      },
      {
        name: "propagate_failure",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const handle = await toolRuntime.spawnChild(child, "model error", {
            failurePolicy: "propagate",
            conversation: { type: "ephemeral" },
          });
          return (await handle.result()).status;
        },
      },
      {
        name: "cross_runtime",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          try {
            await toolRuntime.spawnChild(foreignChild, "foreign");
            return "unexpected";
          } catch (error) {
            return error?.code ?? "unknown";
          }
        },
      },
    ],
  });

  assert.equal((await parent.run("spawn child")).status, "completed");
  assert.equal(spawnedHandle.parentRunId, (await runtime.snapshot(spawnedHandle.runId)).parent);
  const childRequest = requests.find((entry) => entry.messages.at(-1)?.content === "child input");
  assert.ok(childRequest.messages.some((message) => message.content === "child context"));

  assert.equal((await parent.run("saved handle")).status, "completed");
  assert.equal((await savedHandle.result()).status, "completed");
  await savedHandle.release();
  await savedHandle.release();

  assert.equal((await parent.run("conversation modes", { threadId: "parent-thread" })).status, "completed");
  assert.equal(conversationHandles.inherit.parentRunId, conversationHandles.parent.runId);
  assert.equal(conversationHandles.inherit.threadId, "parent-thread");
  assert.equal(conversationHandles.inherit.turnId, conversationHandles.parent.turnId);
  assert.equal(conversationHandles.ephemeral.threadId, undefined);
  assert.equal(conversationHandles.threaded.threadId, "child-thread");

  assert.equal((await parent.run("capture failure")).status, "completed");
  assert.equal((await parent.run("propagate failure")).status, "failed");
  assert.match((await parent.run("cross runtime")).text, /cross_runtime_agent/);
  await runtime.close();
  await foreignRuntime.close();
});

test("Agent IPC and Electron Runtime IPC exchange requests and progress events", async () => {
  const runtime = createRuntime({ maxConcurrency: 1 });
  let operatorRunResolve;
  const operatorRun = new Promise((resolve) => { operatorRunResolve = resolve; });
  let toolSubscriptionResolve;
  const toolSubscriptionReady = new Promise((resolve) => { toolSubscriptionResolve = resolve; });
  let duplicateReplyCode;
  let ipcCancelStartedResolve;
  const ipcCancelStarted = new Promise((resolve) => { ipcCancelStartedResolve = resolve; });
  let ipcCancelSignal;
  let ipcCancelRunId;
  let receiveCancelStartedResolve;
  const receiveCancelStarted = new Promise((resolve) => { receiveCancelStartedResolve = resolve; });
  let receiveCancelSignal;
  const child = await runtime.createAgent({
    id: "ipc-child",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [{
      name: "ipc_child",
      inputSchema: { type: "object" },
      execute: async (_arguments, { runtime: toolRuntime }) => {
        const message = await toolRuntime.ipc.receive();
        await toolRuntime.ipc.reply(message, `child:${message.payload}`);
        return "child replied";
      },
    }, {
      name: "ipc_silent",
      inputSchema: { type: "object" },
      execute: async (_arguments, { runtime: toolRuntime, signal }) => {
        await toolRuntime.ipc.receive();
        return await new Promise((_resolve, reject) => {
          signal.addEventListener("abort", () => reject(new Error("silent child cancelled")), { once: true });
        });
      },
    }],
  });
  const agent = await runtime.createAgent({
    id: "ipc-parent",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    tools: [
      {
        name: "ipc_child",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const handle = await toolRuntime.spawnChild(child, "ipc child", {
            failurePolicy: "capture",
            conversation: { type: "ephemeral" },
          });
          const response = await toolRuntime.ipc.request(handle.runId, "ping", "hello", { timeoutMs: 2_000 });
          await handle.result();
          return response.payload;
        },
      },
      {
        name: "ipc_operator",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          operatorRunResolve(toolRuntime.runId);
          const message = await toolRuntime.ipc.receive();
          await toolRuntime.ipc.reply(message, `approved:${message.payload}`);
          try {
            await toolRuntime.ipc.reply(message, "duplicate");
          } catch (error) {
            duplicateReplyCode = error?.code;
          }
          return "operator replied";
        },
      },
      {
        name: "ipc_timeout",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const handle = await toolRuntime.spawnChild(child, "ipc silent", {
            failurePolicy: "capture",
            conversation: { type: "ephemeral" },
          });
          try {
            await toolRuntime.ipc.request(handle.runId, "never_replied", "wait", { timeoutMs: 5 });
            return "unexpected";
          } catch (error) {
            return error?.code ?? "unknown";
          } finally {
            await handle.cancel("timeout test complete");
            await handle.result();
            await handle.release();
          }
        },
      },
      {
        name: "ipc_cancel",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime, signal }) => {
          ipcCancelSignal = signal;
          ipcCancelRunId = toolRuntime.runId;
          ipcCancelStartedResolve();
          await toolRuntime.ipc.receive();
          return await new Promise((_resolve, reject) => {
            signal.addEventListener("abort", () => reject(new Error("IPC wait cancelled")), { once: true });
          });
        },
      },
      {
        name: "ipc_receive_cancel",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime, signal }) => {
          receiveCancelSignal = signal;
          receiveCancelStartedResolve();
          await toolRuntime.ipc.receive();
          return "unexpected";
        },
      },
      {
        name: "publish_progress",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          await toolRuntime.ipc.publish("progress", "status", "halfway", { priority: 3 });
          return "published";
        },
      },
      {
        name: "subscribe_progress",
        inputSchema: { type: "object" },
        execute: async (_arguments, { runtime: toolRuntime }) => {
          const iterator = toolRuntime.ipc.subscribe("operator:progress")[Symbol.asyncIterator]();
          const next = iterator.next();
          toolSubscriptionResolve();
          try {
            return (await next).value.payload;
          } finally {
            await iterator.return();
          }
        },
      },
    ],
  });

  const childExchange = await agent.run("ipc child");
  assert.equal(childExchange.status, "completed");
  assert.match(childExchange.text, /child:hello/);

  const operatorAgentRun = agent.run("ipc operator");
  const runId = await operatorRun;
  const operatorResponse = await runtime.ipc.request(runId, "approval", "yes", { timeoutMs: 2_000 });
  assert.equal(operatorResponse.payload, "approved:yes");
  assert.equal((await operatorAgentRun).status, "completed");
  assert.equal(duplicateReplyCode, "ipc_reply_invalid");

  const timeout = await agent.run("ipc timeout");
  assert.equal(timeout.status, "completed");
  assert.match(timeout.text, /timeout/);

  const cancelController = new AbortController();
  const cancelled = agent.run("ipc cancel", { signal: cancelController.signal });
  await ipcCancelStarted;
  const requestController = new AbortController();
  const cancelledRequest = runtime.ipc.request(ipcCancelRunId, "cancelled_request", "wait", {
    signal: requestController.signal,
  });
  await new Promise((resolve) => setTimeout(resolve, 5));
  requestController.abort("cancel operator request");
  await assert.rejects(cancelledRequest, (error) => error?.code === "cancelled");
  cancelController.abort("cancel IPC receive");
  await assert.rejects(cancelled, (error) => error?.code === "cancelled");
  assert.equal(ipcCancelSignal.aborted, true);

  const receiveCancelController = new AbortController();
  const receiveCancelled = agent.run("ipc receive cancel", { signal: receiveCancelController.signal });
  await receiveCancelStarted;
  receiveCancelController.abort("cancel pending receive");
  await assert.rejects(receiveCancelled, (error) => error?.code === "cancelled");
  assert.equal(receiveCancelSignal.aborted, true);

  const progress = runtime.ipc.subscribe("progress", { highWaterMark: 1 });
  const progressIterator = progress[Symbol.asyncIterator]();
  const progressNext = progressIterator.next();
  assert.equal((await agent.run("publish progress")).status, "completed");
  const progressMessage = await progressNext;
  assert.equal(progressMessage.value.payload, "halfway");
  assert.equal(progressMessage.value.priority, 3);
  await progressIterator.return();

  const subscribedRun = agent.run("subscribe progress");
  await toolSubscriptionReady;
  await new Promise((resolve) => setTimeout(resolve, 10));
  await runtime.ipc.publish("operator:progress", "status", "operator update");
  assert.match((await subscribedRun).text, /operator update/);

  await assert.rejects(
    runtime.ipc.send("999999", "missing"),
    (error) => error?.code === "ipc_target_unavailable",
  );
  await runtime.close();
});

test("package exports do not expose the Kotlin bridge", async () => {
  await assert.rejects(
    import("@koaks/node/internal/koaks-node-bridge.mjs"),
    (error) => error?.code === "ERR_PACKAGE_PATH_NOT_EXPORTED",
  );
});

test("provider unions, callbacks, skills, custom memory, and MCP gateway adapt cleanly", async () => {
  const runtime = createRuntime();
  const configs = [
    { type: "openai-responses", apiKey: "test", model: "responses", baseUrl },
    { type: "qwen", apiKey: "test", model: "qwen", baseUrl },
    { type: "anthropic", apiKey: "test", model: "claude", baseUrl, maxTokens: 512 },
    { type: "ollama", model: "local", baseUrl },
  ];
  for (const [index, model] of configs.entries()) {
    const configured = await runtime.createAgent({ id: `provider-${index}`, model });
    await configured.close();
  }

  const fallback = await runtime.createAgent({
    id: "fallback",
    model: [
      { type: "openai", apiKey: "test", model: "primary", baseUrl },
      { type: "ollama", model: "secondary", baseUrl },
    ],
    memory: {
      type: "summarizing",
      id: "summary",
      maxTokens: 100,
      model: { type: "openai-responses", apiKey: "test", model: "summary", baseUrl },
    },
  });
  await fallback.close();

  let discovered = 0;
  let loaded = 0;
  const skilled = await runtime.createAgent({
    id: "skilled",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    skills: {
      use: ["inline"],
      sources: [{
        type: "loader",
        loader: {
          discover: async () => { discovered++; return [{ id: "inline", description: "Inline test skill" }]; },
          load: async () => { loaded++; return { description: "Inline test skill", instructions: "Use the inline skill." }; },
        },
      }],
    },
  });
  await skilled.prepare();
  assert.equal(discovered, 1);
  assert.equal(loaded, 1);
  await skilled.close();

  let committed = 0;
  const sharedMemory = {
    type: "custom",
    id: "custom",
    open: async () => ({
      load: async () => ({ transcript: [] }),
      commit: async () => { committed++; },
    }),
  };
  const customMemory = await runtime.createAgent({
    id: "custom-memory",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    memory: sharedMemory,
  });
  assert.equal((await customMemory.run("custom-memory", { threadId: "custom-thread" })).status, "completed");
  assert.equal(committed, 1);
  await customMemory.close();
  const sharedMemoryAgent = await runtime.createAgent({
    id: "custom-memory-2",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    memory: sharedMemory,
  });
  assert.equal((await sharedMemoryAgent.run("shared-memory", { threadId: "custom-thread" })).status, "completed");
  assert.equal(committed, 2);
  await sharedMemoryAgent.close();

  let listed = 0;
  let called = 0;
  let modelEvents = 0;
  let toolHooks = 0;
  let hookExecution;
  let observations = 0;
  const mcp = await runtime.createAgent({
    id: "mcp",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    mcp: [{
      type: "gateway",
      listTools: async () => {
        listed++;
        return [{ name: "mcp_echo", description: "Echo", inputSchema: { type: "object" } }];
      },
      callTool: async (_name, argumentsValue) => { called++; return argumentsValue.value; },
    }],
    hooks: [{
      afterModelEvent: async () => { modelEvents++; return { action: "keep" }; },
      beforeTool: async (_context, execution) => { toolHooks++; hookExecution = execution; return { action: "proceed" }; },
      afterTool: async () => null,
    }],
    listeners: [() => { observations++; }],
  });
  await mcp.prepare();
  const mcpResult = await mcp.run("call mcp", { correlationId: "hook-run" });
  assert.equal(mcpResult.text, "tool=21");
  assert.equal(listed, 1);
  assert.equal(called, 1);
  assert.ok(modelEvents > 0);
  assert.equal(toolHooks, 1);
  assert.equal(hookExecution.correlationId, "hook-run");
  assert.equal(typeof hookExecution.runId, "string");
  assert.equal(hookExecution.signal.aborted, false);
  assert.ok(observations > 0);
  await mcp.close();

  const httpMcp = await runtime.createAgent({
    id: "http-mcp",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    mcp: [{ type: "http", url: `${baseUrl}/mcp`, clientId: 7 }],
  });
  await httpMcp.prepare();
  assert.equal((await httpMcp.run("call mcp")).text, "tool=33");
  assert.equal(requests.filter((entry) => entry.method === "initialize").length, 1);
  await httpMcp.close();

  let terminationChecks = 0;
  const policyAgent = await runtime.createAgent({
    id: "custom-termination",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    termination: {
      decide: async (state) => {
        terminationChecks++;
        return state.globalStep === 0
          ? { action: "stop", message: "stopped by JS policy" }
          : { action: "continue" };
      },
    },
  });
  const terminated = await policyAgent.run("should not call model");
  assert.equal(terminated.status, "terminated");
  assert.equal(terminated.reason.message, "stopped by JS policy");
  assert.equal(terminationChecks, 1);
  await policyAgent.close();

  let errorChecks = 0;
  const errorPolicyAgent = await runtime.createAgent({
    id: "custom-error-policy",
    model: { type: "openai", apiKey: "test", model: "fixture", baseUrl },
    errorPolicy: {
      type: "custom",
      decide: async (error) => {
        errorChecks++;
        assert.equal(error.type, "model_error");
        return { action: "propagate" };
      },
    },
  });
  const modelFailure = await errorPolicyAgent.run("model error");
  assert.equal(modelFailure.status, "failed");
  assert.equal(errorChecks, 1);
  await errorPolicyAgent.close();

  await runtime.close();
});
