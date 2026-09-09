import { AsyncLocalStorage } from "node:async_hooks";
import type {
  ChildSpawnOptions,
  MessageEnvelope,
  MessageRequestOptions,
  MessageSendOptions,
  MessageSubscriptionOptions,
  KoaksAgent,
  ModelItem,
  RunHandle,
  RuntimeCoordination,
  RuntimeMessageRequestOptions,
  StreamOptions,
  ToolExecutionContext,
  RunMessaging,
  ToolResources,
  AgentContext,
} from "./types.js";
import type { BoundedAsyncIterable, BridgeClient, CallbackRegistry } from "./internal.js";
import { KoaksError } from "./internal.js";

export interface HandleDescriptor {
  handleId: string;
  runId: string;
  agentId: string;
  threadId?: string;
  correlationId?: string;
  parentRunId?: string;
}

export interface ToolExecutionPayload {
  executionId: string;
  callId: string;
  toolName: string;
  argumentsJson: string;
  runId: string;
  agentId: string;
  threadId?: string;
  correlationId?: string;
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
    options: StreamOptions | MessageSubscriptionOptions,
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

function coordinationParams(
  type: string,
  payload: string,
  options: MessageSendOptions,
): Record<string, unknown> {
  return { type, payload, ...options };
}

type BridgeMessageEnvelope = MessageEnvelope & { replyToken?: string };

class AgentContextImpl implements AgentContext {
  readonly runId: string;
  readonly agentId: string;
  readonly threadId?: string;
  readonly correlationId?: string;
  readonly resources: ToolResources;
  private readonly messaging: RunMessaging;
  private active = true;

  constructor(
    private readonly host: ToolRuntimeHost,
    private readonly executionId: string,
    metadata: Pick<ToolExecutionPayload, "runId" | "agentId" | "threadId" | "correlationId">,
  ) {
    this.runId = metadata.runId;
    this.agentId = metadata.agentId;
    if (metadata.threadId !== undefined) this.threadId = metadata.threadId;
    if (metadata.correlationId !== undefined) this.correlationId = metadata.correlationId;
    this.resources = new ToolResourcesImpl(host, executionId, () => this.assertActive());
    this.messaging = new RunMessagingImpl(host, executionId, () => this.assertActive());
  }

  send(toRunId: string, type: string, payload = "", options: MessageSendOptions = {}): Promise<void> {
    return this.messaging.send(toRunId, type, payload, options);
  }

  receive(): Promise<MessageEnvelope> {
    return this.messaging.receive();
  }

  request(toRunId: string, type: string, payload = "", options: MessageRequestOptions = {}): Promise<MessageEnvelope> {
    return this.messaging.request(toRunId, type, payload, options);
  }

  reply(message: MessageEnvelope, payload = "", options: Pick<MessageSendOptions, "context"> = {}): Promise<void> {
    return this.messaging.reply(message, payload, options);
  }

  publish(topic: string, type: string, payload = "", options: MessageSendOptions = {}): Promise<void> {
    return this.messaging.publish(topic, type, payload, options);
  }

  subscribe(topic: string, options: MessageSubscriptionOptions = {}): AsyncIterable<MessageEnvelope> {
    return this.messaging.subscribe(topic, options);
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

  async listChildren(): Promise<import("./types.js").ChildAgentInfo[]> {
    this.assertActive();
    return this.host.client.request("tool.list_children", { executionId: this.executionId });
  }

  async sendTo(agentId: string, message: string): Promise<import("./types.js").SendResult> {
    this.assertActive();
    return this.host.client.request("tool.send_to_child", { executionId: this.executionId, agentId, message });
  }

  expire(): void {
    this.active = false;
  }

  private assertActive(): void {
    if (!this.active) {
      throw new KoaksError("tool_context_expired", `Tool AgentContext '${this.executionId}' has expired`);
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

class RunMessagingImpl implements RunMessaging {
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
    options: MessageSendOptions = {},
  ): Promise<void> {
    this.assertActive();
    await this.host.client.request("tool.coordination.send", {
      executionId: this.executionId,
      toRunId,
      ...coordinationParams(type, payload, options),
    });
  }

  async receive(): Promise<MessageEnvelope> {
    this.assertActive();
    const raw = await this.host.client.request<BridgeMessageEnvelope>("tool.coordination.receive", {
      executionId: this.executionId,
    });
    return this.materialize(raw);
  }

  async request(
    toRunId: string,
    type: string,
    payload = "",
    options: MessageRequestOptions = {},
  ): Promise<MessageEnvelope> {
    this.assertActive();
    return await this.host.client.request("tool.coordination.request", {
      executionId: this.executionId,
      toRunId,
      ...coordinationParams(type, payload, options),
      timeoutMs: options.timeoutMs,
    });
  }

  async reply(
    message: MessageEnvelope,
    payload = "",
    options: Pick<MessageSendOptions, "context"> = {},
  ): Promise<void> {
    this.assertActive();
    const token = this.replyTokens.get(message as object);
    if (token === undefined) {
      throw new KoaksError("ipc_reply_invalid", "IPC message is not a pending request or was already replied to");
    }
    this.replyTokens.delete(message as object);
    await this.host.client.request("tool.coordination.reply", {
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
    options: MessageSendOptions = {},
  ): Promise<void> {
    this.assertActive();
    await this.host.client.request("tool.coordination.publish", {
      executionId: this.executionId,
      topic,
      ...coordinationParams(type, payload, options),
    });
  }

  subscribe(topic: string, options: MessageSubscriptionOptions = {}): AsyncIterable<MessageEnvelope> {
    this.assertActive();
    return this.host.createStream<MessageEnvelope>(
      "tool.coordination.subscribe",
      { executionId: this.executionId, topic },
      options,
    );
  }

  private materialize(raw: BridgeMessageEnvelope): MessageEnvelope {
    const { replyToken, ...message } = raw;
    if (replyToken !== undefined) this.replyTokens.set(message, replyToken);
    return message;
  }
}

class RuntimeCoordinationImpl implements RuntimeCoordination {
  constructor(private readonly host: ToolRuntimeHost) {}

  async send(
    toRunId: string,
    type: string,
    payload = "",
    options: MessageSendOptions = {},
  ): Promise<void> {
    await this.host.client.request("runtime.coordination.send", { toRunId, ...coordinationParams(type, payload, options) });
  }

  async request(
    toRunId: string,
    type: string,
    payload = "",
    options: RuntimeMessageRequestOptions = {},
  ): Promise<MessageEnvelope> {
    return await this.host.runOperation(
      "runtime.coordination.request",
      { toRunId, ...coordinationParams(type, payload, options), timeoutMs: options.timeoutMs },
      options.signal,
    );
  }

  async publish(
    topic: string,
    type: string,
    payload = "",
    options: MessageSendOptions = {},
  ): Promise<void> {
    await this.host.client.request("runtime.coordination.publish", { topic, ...coordinationParams(type, payload, options) });
  }

  subscribe(topic: string, options: MessageSubscriptionOptions = {}): AsyncIterable<MessageEnvelope> {
    return this.host.createStream("runtime.coordination.subscribe", { topic }, options);
  }
}

export function createRuntimeCoordination(host: ToolRuntimeHost): RuntimeCoordination {
  return new RuntimeCoordinationImpl(host);
}

export async function runToolCallback<T>(
  host: ToolRuntimeHost,
  payload: ToolExecutionPayload,
  signal: AbortSignal,
  callback: (context: ToolExecutionContext) => T | Promise<T>,
): Promise<T> {
  const runtime = new AgentContextImpl(host, payload.executionId, payload);
  const context: ToolExecutionContext = {
    executionId: payload.executionId,
    callId: payload.callId,
    toolName: payload.toolName,
    ...(payload.correlationId !== undefined ? { correlationId: payload.correlationId } : {}),
    signal,
    runtime,
    reportProgress: async (progress) => {
      await host.client.request("tool.progress", { executionId: payload.executionId, progress });
    },
  };
  try {
    return await toolExecutionStorage.run(
      { executionId: payload.executionId },
      async () => await callback(context),
    );
  } finally {
    runtime.expire();
  }
}
