import { createKoaksBridge } from "../internal/koaks-node-bridge.mjs";
import { prepareAgentConfig, prepareRuntimeConfig } from "./config.js";
import {
  createRuntimeIpc,
  currentToolExecutionId,
} from "./tool-runtime.js";
import type { HandleDescriptor, ToolRuntimeHost } from "./tool-runtime.js";
import {
  BoundedAsyncIterable,
  BridgeClient,
  CallbackRegistry,
  KoaksBridgeError,
  KoaksCancelledError,
  KoaksConfigError,
  KoaksError,
  errorFromBridge,
  throwIfAborted,
  toBridgeValue,
} from "./internal.js";
import type {
  AgentConfig,
  AgentEvent,
  AgentResult,
  ContextScope,
  KoaksAgent,
  KoaksRuntime,
  ModelItem,
  OutputSchema,
  ReapOptions,
  RunHandle,
  RunOptions,
  RunSnapshot,
  RuntimeEvent,
  RuntimeMetrics,
  RuntimeIpc,
  RuntimeOptions,
  StreamOptions,
  StructuredAgentResult,
  SupervisedHandle,
  SupervisionPolicy,
  TaskDefinition,
  ThreadSnapshot,
} from "./types.js";

export * from "./types.js";
export { KoaksBridgeError, KoaksCancelledError, KoaksConfigError, KoaksError };

interface AgentDescriptor {
  agentKey: string;
  id: string;
  name: string;
}

interface SupervisedDescriptor {
  supervisedId: string;
}

interface StreamMessage<T> {
  type: "next" | "complete" | "error";
  value?: T;
  error?: { type?: string; message?: string; stack?: string };
}

class RunHandleImpl implements RunHandle {
  readonly runId: string;
  readonly agentId: string;
  readonly threadId?: string;
  readonly turnId?: string;
  readonly parentRunId?: string;
  private readonly handleId: string;
  private abortListener: (() => void) | undefined;
  private abortSignal: AbortSignal | undefined;
  private released = false;

  constructor(
    descriptor: HandleDescriptor,
    private readonly runtime: KoaksRuntimeImpl,
    signal?: AbortSignal,
  ) {
    this.handleId = descriptor.handleId;
    this.runId = descriptor.runId;
    this.agentId = descriptor.agentId;
    if (descriptor.threadId !== undefined) this.threadId = descriptor.threadId;
    if (descriptor.turnId !== undefined) this.turnId = descriptor.turnId;
    if (descriptor.parentRunId !== undefined) this.parentRunId = descriptor.parentRunId;
    if (signal !== undefined) {
      this.abortSignal = signal;
      this.abortListener = () => { void this.cancel(String(signal.reason ?? "aborted")); };
      signal.addEventListener("abort", this.abortListener, { once: true });
    }
  }

  async result(): Promise<AgentResult> {
    try {
      return await this.runtime.client.request<AgentResult>("handle.result", {
        handleId: this.handleId,
        executionId: currentToolExecutionId(),
      });
    } finally {
      this.removeAbortListener();
    }
  }

  async cancel(reason?: string): Promise<void> {
    await this.runtime.client.request("handle.cancel", { handleId: this.handleId, reason });
  }

  async pause(): Promise<void> {
    await this.runtime.client.request("handle.pause", { handleId: this.handleId });
  }

  async resume(): Promise<void> {
    await this.runtime.client.request("handle.resume", { handleId: this.handleId });
  }

  async snapshot(): Promise<RunSnapshot> {
    return await this.runtime.client.request("handle.snapshot", { handleId: this.handleId });
  }

  updates(options: StreamOptions = {}): AsyncIterable<RunSnapshot> {
    return this.runtime.createStream<RunSnapshot>(
      "handle.updates",
      { handleId: this.handleId, executionId: currentToolExecutionId() },
      options,
    );
  }

  async release(): Promise<void> {
    if (this.released) return;
    this.released = true;
    this.removeAbortListener();
    await this.runtime.client.request("handle.release", { handleId: this.handleId });
  }

  private removeAbortListener(): void {
    if (this.abortListener !== undefined) {
      this.abortSignal?.removeEventListener("abort", this.abortListener);
      this.abortListener = undefined;
      this.abortSignal = undefined;
    }
  }
}

class SupervisedHandleImpl implements SupervisedHandle {
  constructor(
    private readonly id: string,
    private readonly runtime: KoaksRuntimeImpl,
    private readonly callbackIds: string[],
  ) {}

  async result(): Promise<AgentResult> {
    try {
      return await this.runtime.client.request("supervised.result", { supervisedId: this.id });
    } finally {
      this.runtime.callbacks.release(this.callbackIds);
      await this.runtime.client.request("supervised.release", { supervisedId: this.id }).catch(() => undefined);
    }
  }

  async cancel(reason?: string): Promise<void> {
    try {
      await this.runtime.client.request("supervised.cancel", { supervisedId: this.id, reason });
    } finally {
      this.runtime.callbacks.release(this.callbackIds);
      await this.runtime.client.request("supervised.release", { supervisedId: this.id }).catch(() => undefined);
    }
  }
}

class KoaksAgentImpl implements KoaksAgent {
  readonly id: string;
  readonly name: string;
  readonly key: string;
  private closed = false;
  private readonly streams = new Set<BoundedAsyncIterable<unknown>>();

  constructor(
    descriptor: AgentDescriptor,
    private readonly runtime: KoaksRuntimeImpl,
    private readonly callbackIds: string[],
  ) {
    this.key = descriptor.agentKey;
    this.id = descriptor.id;
    this.name = descriptor.name;
  }

  async prepare(): Promise<void> {
    this.assertOpen();
    await this.runtime.client.request("agent.prepare", { agentKey: this.key });
  }

  async run(input: string, options: RunOptions = {}): Promise<AgentResult> {
    this.assertOpen();
    throwIfAborted(options.signal);
    const handle = await this.spawn(input, options) as RunHandleImpl;
    try {
      return await handle.result();
    } finally {
      await handle.release().catch(() => undefined);
    }
  }

  async runStructured<T>(
    input: string,
    output: OutputSchema,
    options: RunOptions = {},
  ): Promise<StructuredAgentResult<T>> {
    this.assertOpen();
    const result = await this.runtime.runOperation<AgentResult>(
      "agent.run_structured",
      { agentKey: this.key, input, output, options },
      options.signal,
    );
    if (result.status !== "completed") return result;
    try {
      return { ...result, output: JSON.parse(result.text) as T };
    } catch (cause) {
      throw new KoaksBridgeError("structured_output_parse_error", "Koaks returned invalid structured JSON", undefined);
    }
  }

  stream(input: string, options: StreamOptions = {}): AsyncIterable<AgentEvent> {
    this.assertOpen();
    return this.runtime.createStream<AgentEvent>(
      "agent.stream",
      { agentKey: this.key, input, options },
      options,
      this.streams,
    );
  }

  async spawn(input: string, options: RunOptions = {}): Promise<RunHandle> {
    this.assertOpen();
    throwIfAborted(options.signal);
    const descriptor = await this.runtime.client.request<HandleDescriptor>("agent.spawn", {
      agentKey: this.key,
      input,
      options,
    });
    const handle = this.runtime.createRunHandle(descriptor, options.signal);
    if (options.signal?.aborted === true) {
      await handle.cancel(String(options.signal.reason ?? "aborted"));
    }
    return handle;
  }

  resume(threadId: string, options: StreamOptions = {}): AsyncIterable<AgentEvent> {
    this.assertOpen();
    return this.runtime.createStream<AgentEvent>(
      "agent.resume",
      { agentKey: this.key, threadId, options },
      options,
      this.streams,
    );
  }

  async resumeRun(threadId: string, options: RunOptions = {}): Promise<AgentResult> {
    this.assertOpen();
    return await this.runtime.runOperation(
      "agent.resume_run",
      { agentKey: this.key, threadId, options },
      options.signal,
    );
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    await Promise.all([...this.streams].map(async (stream) => { await stream.return(); }));
    try {
      await this.runtime.client.request("agent.close", { agentKey: this.key });
    } finally {
      this.runtime.deferAgentCallbacks(this.callbackIds);
      this.runtime.removeAgent(this);
    }
  }

  markReplaced(): void {
    if (this.closed) return;
    this.closed = true;
    for (const stream of this.streams) void stream.return();
    this.runtime.deferAgentCallbacks(this.callbackIds);
  }

  belongsTo(runtime: KoaksRuntimeImpl): boolean {
    return this.runtime === runtime;
  }

  private assertOpen(): void {
    if (this.closed) throw new KoaksError("agent_closed", `Agent '${this.id}' is closed`);
  }
}

class KoaksRuntimeImpl implements KoaksRuntime, ToolRuntimeHost {
  readonly callbacks: CallbackRegistry;
  readonly client: BridgeClient;
  readonly ipc: RuntimeIpc;
  private readonly agents = new Map<string, KoaksAgentImpl>();
  private readonly streams = new Set<BoundedAsyncIterable<unknown>>();
  private readonly deferredAgentCallbackIds = new Set<string>();
  private operationSequence = 0;
  private closed = false;

  constructor(
    options: RuntimeOptions,
    readonly highWaterMark: number,
    private readonly runtimeCallbackIds: string[],
    callbacks: CallbackRegistry,
  ) {
    this.callbacks = callbacks;
    const prepared = prepareRuntimeConfig(options, callbacks);
    runtimeCallbackIds.push(...prepared.callbackIds);
    const bridge = createKoaksBridge(JSON.stringify(prepared.value), callbacks.invoke, callbacks.notify);
    this.client = new BridgeClient(bridge);
    this.ipc = createRuntimeIpc(this);
  }

  async createAgent(config: AgentConfig): Promise<KoaksAgent> {
    this.assertOpen();
    const prepared = prepareAgentConfig(config, this.callbacks, this);
    try {
      const descriptor = await this.client.request<AgentDescriptor>("runtime.create_agent", { config: prepared.value });
      const agent = new KoaksAgentImpl(descriptor, this, prepared.callbackIds);
      this.agents.set(agent.id, agent);
      return agent;
    } catch (error) {
      this.callbacks.release(prepared.callbackIds);
      throw error;
    }
  }

  async replaceAgent(config: AgentConfig): Promise<KoaksAgent> {
    this.assertOpen();
    const prepared = prepareAgentConfig(config, this.callbacks, this);
    try {
      const descriptor = await this.client.request<AgentDescriptor>("runtime.replace_agent", { config: prepared.value });
      const previous = this.agents.get(config.id);
      const agent = new KoaksAgentImpl(descriptor, this, prepared.callbackIds);
      previous?.markReplaced();
      this.agents.set(agent.id, agent);
      return agent;
    } catch (error) {
      this.callbacks.release(prepared.callbackIds);
      throw error;
    }
  }

  events(options: StreamOptions = {}): AsyncIterable<RuntimeEvent> {
    this.assertOpen();
    return this.createStream("runtime.events", {}, options);
  }

  async metrics(): Promise<RuntimeMetrics> {
    return await this.client.request("runtime.metrics");
  }

  async runs(): Promise<RunSnapshot[]> {
    return await this.client.request("runtime.runs");
  }

  async snapshot(runId: string): Promise<RunSnapshot | undefined> {
    return (await this.client.request<RunSnapshot | null>("runtime.snapshot", { runId })) ?? undefined;
  }

  async threadSnapshot(threadId: string): Promise<ThreadSnapshot | undefined> {
    return (await this.client.request<ThreadSnapshot | null>("runtime.thread_snapshot", { threadId })) ?? undefined;
  }

  async putContext(items: ModelItem[], scope: ContextScope = { type: "global" }): Promise<string> {
    return await this.client.request("runtime.put_context", { items, scope });
  }

  async deltaContext(parentRef: string, items: ModelItem[], scope: ContextScope = { type: "global" }): Promise<string> {
    return await this.client.request("runtime.delta_context", { parentRef, items, scope });
  }

  async resolveContext(ref: string, requesterRunId?: string): Promise<ModelItem[]> {
    return await this.client.request("runtime.resolve_context", { ref, requesterRunId });
  }

  async submit(tasks: TaskDefinition[]): Promise<Record<string, AgentResult>> {
    this.assertOpen();
    const callbackIds: string[] = [];
    const normalized = tasks.map((task) => {
      const agent = task.agent;
      if (!(agent instanceof KoaksAgentImpl) || !agent.belongsTo(this)) {
        throw new KoaksConfigError(`Task '${task.id}' uses an agent from another runtime`);
      }
      const value: Record<string, unknown> = {
        id: task.id,
        agentKey: agent.key,
        priority: task.priority,
        dependsOn: task.dependsOn ?? [],
      };
      const input = task.input;
      if (typeof input === "string") value.input = input;
      else {
        value.inputCallbackId = this.callbacks.registerInvoke(async (rawPayload) => {
          const payload = rawPayload as { dependencies: Record<string, AgentResult> };
          return await input(payload.dependencies);
        }, callbackIds);
      }
      return value;
    });
    try {
      return await this.client.request("runtime.submit", { tasks: normalized });
    } finally {
      this.callbacks.release(callbackIds);
    }
  }

  async spawnSupervised(
    agent: KoaksAgent,
    input: string,
    policy: SupervisionPolicy = {},
  ): Promise<SupervisedHandle> {
    this.assertOpen();
    if (!(agent instanceof KoaksAgentImpl) || !agent.belongsTo(this)) {
      throw new KoaksConfigError("spawnSupervised requires an agent from this runtime");
    }
    const callbackIds: string[] = [];
    const normalizedPolicy = toBridgeValue(policy) as Record<string, unknown>;
    if (policy.recover !== undefined) {
      normalizedPolicy.recover_callback_id = this.callbacks.registerInvoke(async (rawPayload) => {
        const payload = rawPayload as { attempt: number; last: AgentResult };
        return await policy.recover?.(payload.attempt, payload.last);
      }, callbackIds);
    }
    try {
      const descriptor = await this.client.request<SupervisedDescriptor>("runtime.spawn_supervised", {
        agentKey: agent.key,
        input,
        policy: normalizedPolicy,
      });
      return new SupervisedHandleImpl(descriptor.supervisedId, this, callbackIds);
    } catch (error) {
      this.callbacks.release(callbackIds);
      throw error;
    }
  }

  async reap(options: ReapOptions = {}): Promise<number> {
    return await this.client.request("runtime.reap", { ...options });
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    await Promise.all([...this.streams].map(async (stream) => { await stream.return(); }));
    try {
      await this.client.request("runtime.close");
    } finally {
      for (const agent of this.agents.values()) agent.markReplaced();
      this.agents.clear();
      this.callbacks.release(this.runtimeCallbackIds);
      this.callbacks.release(this.deferredAgentCallbackIds);
      this.deferredAgentCallbackIds.clear();
    }
  }

  createStream<T>(
    method: string,
    params: Record<string, unknown>,
    options: StreamOptions,
    ownerStreams?: Set<BoundedAsyncIterable<unknown>>,
  ): BoundedAsyncIterable<T> {
    throwIfAborted(options.signal);
    let subscriptionId: string | undefined;
    let callbackId: string | undefined;
    let abortListener: (() => void) | undefined;
    const remove = (stream: BoundedAsyncIterable<T>): void => {
      this.streams.delete(stream as BoundedAsyncIterable<unknown>);
      ownerStreams?.delete(stream as BoundedAsyncIterable<unknown>);
      if (callbackId !== undefined) this.callbacks.release([callbackId]);
      if (abortListener !== undefined && options.signal !== undefined) {
        options.signal.removeEventListener("abort", abortListener);
        abortListener = undefined;
      }
    };
    let stream!: BoundedAsyncIterable<T>;
    const start = async (): Promise<string> => {
      callbackId = this.callbacks.registerInvoke(async (rawMessage) => {
        const message = rawMessage as StreamMessage<T>;
        if (message.type === "next") {
          if (message.value !== undefined) await stream.push(message.value);
        } else if (message.type === "complete") {
          stream.complete();
          remove(stream);
        } else {
          stream.fail(errorFromBridge(message.error ?? {}));
          remove(stream);
        }
        return null;
      });
      subscriptionId = await this.client.request<string>(method, { ...params, callbackId });
      return subscriptionId;
    };
    const startPromise = start();
    stream = new BoundedAsyncIterable<T>(options.highWaterMark ?? this.highWaterMark, async () => {
      try {
        const id = subscriptionId ?? await startPromise;
        await this.client.request("subscription.cancel", { subscriptionId: id });
      } catch {
        // Startup failures are delivered through the iterable.
      } finally {
        remove(stream);
      }
    });
    this.streams.add(stream as BoundedAsyncIterable<unknown>);
    ownerStreams?.add(stream as BoundedAsyncIterable<unknown>);
    void startPromise.catch((error: unknown) => {
      stream.fail(error);
      remove(stream);
    });
    if (options.signal !== undefined) {
      abortListener = () => { void stream.return(); };
      options.signal.addEventListener("abort", abortListener, { once: true });
    }
    return stream;
  }

  async runOperation<T>(
    method: string,
    params: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<T> {
    throwIfAborted(signal);
    const operationId = `operation-${++this.operationSequence}`;
    const abort = () => {
      void this.client.request("operation.cancel", { operationId, reason: String(signal?.reason ?? "aborted") });
    };
    signal?.addEventListener("abort", abort, { once: true });
    try {
      return await this.client.request(method, { ...params, operationId });
    } finally {
      signal?.removeEventListener("abort", abort);
    }
  }

  resolveAgentKey(agent: KoaksAgent): string {
    if (!(agent instanceof KoaksAgentImpl) || !agent.belongsTo(this)) {
      throw new KoaksError("cross_runtime_agent", "spawnChild requires an Agent from the same KoaksRuntime");
    }
    return agent.key;
  }

  createRunHandle(descriptor: HandleDescriptor, signal?: AbortSignal): RunHandle {
    return new RunHandleImpl(descriptor, this, signal);
  }

  removeAgent(agent: KoaksAgentImpl): void {
    if (this.agents.get(agent.id) === agent) this.agents.delete(agent.id);
  }

  deferAgentCallbacks(ids: Iterable<string>): void {
    for (const id of ids) this.deferredAgentCallbackIds.add(id);
  }

  private assertOpen(): void {
    if (this.closed) throw new KoaksError("runtime_closed", "Koaks runtime is closed");
  }
}

export function createRuntime(options: RuntimeOptions = {}): KoaksRuntime {
  const highWaterMark = options.highWaterMark ?? 64;
  if (!Number.isInteger(highWaterMark) || highWaterMark < 1) {
    throw new KoaksConfigError("highWaterMark must be a positive integer");
  }
  const callbacks = new CallbackRegistry();
  const runtimeCallbackIds: string[] = [];
  try {
    return new KoaksRuntimeImpl(options, highWaterMark, runtimeCallbackIds, callbacks);
  } catch (cause) {
    callbacks.release(runtimeCallbackIds);
    throw new KoaksConfigError(cause instanceof Error ? cause.message : String(cause));
  }
}
