import { AsyncLocalStorage } from "node:async_hooks";
import type {
  ChildSpawnOptions,
  IpcMessage,
  IpcRequestOptions,
  IpcSendOptions,
  IpcSubscriptionOptions,
  KoaksAgent,
  ModelItem,
  RunHandle,
  RuntimeIpc,
  RuntimeIpcRequestOptions,
  StreamOptions,
  ToolContextStore,
  ToolExecutionContext,
  ToolIpc,
  ToolResources,
  ToolRuntimeContext,
} from "./types.js";
import type { BoundedAsyncIterable, BridgeClient, CallbackRegistry } from "./internal.js";
import { KoaksError } from "./internal.js";

export interface HandleDescriptor {
  handleId: string;
  runId: string;
  agentId: string;
  threadId?: string;
  turnId?: string;
  parentRunId?: string;
}

export interface ToolExecutionPayload {
  executionId: string;
  argumentsJson: string;
  runId: string;
  agentId: string;
  threadId?: string;
  turnId?: string;
}

export interface ToolRuntimeHost {
  readonly client: BridgeClient;
  readonly callbacks: CallbackRegistry;
  readonly highWaterMark: number;
  resolveAgentKey(agent: KoaksAgent): string;
  createRunHandle(descriptor: HandleDescriptor, signal?: AbortSignal): RunHandle;
  createStream<T>(
    method: string,
    params: Record<string, unknown>,
    options: StreamOptions | IpcSubscriptionOptions,
    ownerStreams?: Set<BoundedAsyncIterable<unknown>>,
  ): BoundedAsyncIterable<T>;
  runOperation<T>(method: string, params: Record<string, unknown>, signal?: AbortSignal): Promise<T>;
}

interface ActiveToolExecution {
  executionId: string;
}

const toolExecutionStorage = new AsyncLocalStorage<ActiveToolExecution>();

export function currentToolExecutionId(): string | undefined {
  return toolExecutionStorage.getStore()?.executionId;
}

function ipcParams(
  type: string,
  payload: string,
  options: IpcSendOptions,
): Record<string, unknown> {
  return { type, payload, ...options };
}

type BridgeIpcMessage = IpcMessage & { replyToken?: string };

class ToolRuntimeContextImpl implements ToolRuntimeContext {
  readonly runId: string;
  readonly agentId: string;
  readonly threadId?: string;
  readonly turnId?: string;
  readonly resources: ToolResources;
  readonly context: ToolContextStore;
  readonly ipc: ToolIpc;
  private active = true;

  constructor(
    private readonly host: ToolRuntimeHost,
    private readonly executionId: string,
    metadata: Pick<ToolExecutionPayload, "runId" | "agentId" | "threadId" | "turnId">,
  ) {
    this.runId = metadata.runId;
    this.agentId = metadata.agentId;
    if (metadata.threadId !== undefined) this.threadId = metadata.threadId;
    if (metadata.turnId !== undefined) this.turnId = metadata.turnId;
    this.resources = new ToolResourcesImpl(host, executionId, () => this.assertActive());
    this.context = new ToolContextStoreImpl(host, executionId, () => this.assertActive());
    this.ipc = new ToolIpcImpl(host, executionId, () => this.assertActive());
  }

  async spawnChild(
    agent: KoaksAgent,
    input: string,
    options: ChildSpawnOptions = {},
  ): Promise<RunHandle> {
    this.assertActive();
    const descriptor = await this.host.client.request<HandleDescriptor>("tool.spawn_child", {
      executionId: this.executionId,
      agentKey: this.host.resolveAgentKey(agent),
      input,
      options,
    });
    return this.host.createRunHandle(descriptor);
  }

  expire(): void {
    this.active = false;
  }

  private assertActive(): void {
    if (!this.active) {
      throw new KoaksError("tool_context_expired", `Tool RuntimeContext '${this.executionId}' has expired`);
    }
  }
}

class ToolResourcesImpl implements ToolResources {
  constructor(
    private readonly host: ToolRuntimeHost,
    private readonly executionId: string,
    private readonly assertActive: () => void,
  ) {}

  async withResource<T>(
    id: string,
    block: () => T | Promise<T>,
    options: { mode?: "read" | "write" } = {},
  ): Promise<T> {
    this.assertActive();
    let value!: T;
    let callbackFailed = false;
    let callbackError: unknown;
    const callbackId = this.host.callbacks.registerInvoke(async () => {
      try {
        value = await toolExecutionStorage.run(
          { executionId: this.executionId },
          async () => await block(),
        );
        return null;
      } catch (error) {
        callbackFailed = true;
        callbackError = error;
        throw error;
      }
    });
    try {
      await this.host.client.request("tool.resource.with", {
        executionId: this.executionId,
        resourceId: id,
        mode: options.mode ?? "write",
        callbackId,
      });
      return value;
    } catch (error) {
      if (callbackFailed) throw callbackError;
      throw error;
    } finally {
      this.host.callbacks.release([callbackId]);
    }
  }
}

class ToolContextStoreImpl implements ToolContextStore {
  constructor(
    private readonly host: ToolRuntimeHost,
    private readonly executionId: string,
    private readonly assertActive: () => void,
  ) {}

  async put(items: ModelItem[], scope: "private" | "task" | "global" = "private"): Promise<string> {
    this.assertActive();
    return await this.host.client.request("tool.context.put", { executionId: this.executionId, items, scope });
  }

  async delta(
    parentRef: string,
    items: ModelItem[],
    scope: "private" | "task" | "global" = "private",
  ): Promise<string> {
    this.assertActive();
    return await this.host.client.request("tool.context.delta", {
      executionId: this.executionId,
      parentRef,
      items,
      scope,
    });
  }

  async resolve(ref: string): Promise<ModelItem[]> {
    this.assertActive();
    return await this.host.client.request("tool.context.resolve", { executionId: this.executionId, ref });
  }
}

class ToolIpcImpl implements ToolIpc {
  private readonly replyTokens = new WeakMap<object, string>();

  constructor(
    private readonly host: ToolRuntimeHost,
    private readonly executionId: string,
    private readonly assertActive: () => void,
  ) {}

  async send(
    toRunId: string,
    type: string,
    payload = "",
    options: IpcSendOptions = {},
  ): Promise<void> {
    this.assertActive();
    await this.host.client.request("tool.ipc.send", {
      executionId: this.executionId,
      toRunId,
      ...ipcParams(type, payload, options),
    });
  }

  async receive(): Promise<IpcMessage> {
    this.assertActive();
    const raw = await this.host.client.request<BridgeIpcMessage>("tool.ipc.receive", {
      executionId: this.executionId,
    });
    return this.materialize(raw);
  }

  async request(
    toRunId: string,
    type: string,
    payload = "",
    options: IpcRequestOptions = {},
  ): Promise<IpcMessage> {
    this.assertActive();
    return await this.host.client.request("tool.ipc.request", {
      executionId: this.executionId,
      toRunId,
      ...ipcParams(type, payload, options),
      timeoutMs: options.timeoutMs,
    });
  }

  async reply(
    message: IpcMessage,
    payload = "",
    options: Pick<IpcSendOptions, "contextRefs"> = {},
  ): Promise<void> {
    this.assertActive();
    const token = this.replyTokens.get(message as object);
    if (token === undefined) {
      throw new KoaksError("ipc_reply_invalid", "IPC message is not a pending request or was already replied to");
    }
    this.replyTokens.delete(message as object);
    await this.host.client.request("tool.ipc.reply", {
      executionId: this.executionId,
      replyToken: token,
      payload,
      ...options,
    });
  }

  async publish(
    topic: string,
    type: string,
    payload = "",
    options: IpcSendOptions = {},
  ): Promise<void> {
    this.assertActive();
    await this.host.client.request("tool.ipc.publish", {
      executionId: this.executionId,
      topic,
      ...ipcParams(type, payload, options),
    });
  }

  subscribe(topic: string, options: IpcSubscriptionOptions = {}): AsyncIterable<IpcMessage> {
    this.assertActive();
    return this.host.createStream<IpcMessage>(
      "tool.ipc.subscribe",
      { executionId: this.executionId, topic },
      options,
    );
  }

  private materialize(raw: BridgeIpcMessage): IpcMessage {
    const { replyToken, ...message } = raw;
    if (replyToken !== undefined) this.replyTokens.set(message, replyToken);
    return message;
  }
}

class RuntimeIpcImpl implements RuntimeIpc {
  constructor(private readonly host: ToolRuntimeHost) {}

  async send(
    toRunId: string,
    type: string,
    payload = "",
    options: IpcSendOptions = {},
  ): Promise<void> {
    await this.host.client.request("runtime.ipc.send", { toRunId, ...ipcParams(type, payload, options) });
  }

  async request(
    toRunId: string,
    type: string,
    payload = "",
    options: RuntimeIpcRequestOptions = {},
  ): Promise<IpcMessage> {
    return await this.host.runOperation(
      "runtime.ipc.request",
      { toRunId, ...ipcParams(type, payload, options), timeoutMs: options.timeoutMs },
      options.signal,
    );
  }

  async publish(
    topic: string,
    type: string,
    payload = "",
    options: IpcSendOptions = {},
  ): Promise<void> {
    await this.host.client.request("runtime.ipc.publish", { topic, ...ipcParams(type, payload, options) });
  }

  subscribe(topic: string, options: IpcSubscriptionOptions = {}): AsyncIterable<IpcMessage> {
    return this.host.createStream("runtime.ipc.subscribe", { topic }, options);
  }
}

export function createRuntimeIpc(host: ToolRuntimeHost): RuntimeIpc {
  return new RuntimeIpcImpl(host);
}

export async function runToolCallback<T>(
  host: ToolRuntimeHost,
  payload: ToolExecutionPayload,
  signal: AbortSignal,
  callback: (context: ToolExecutionContext) => T | Promise<T>,
): Promise<T> {
  const runtime = new ToolRuntimeContextImpl(host, payload.executionId, payload);
  const context: ToolExecutionContext = { executionId: payload.executionId, signal, runtime };
  try {
    return await toolExecutionStorage.run(
      { executionId: payload.executionId },
      async () => await callback(context),
    );
  } finally {
    runtime.expire();
  }
}
