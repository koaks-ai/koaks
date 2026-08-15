import { CallbackRegistry, toBridgeValue } from "./internal.js";
import { runToolCallback } from "./tool-runtime.js";
import type { ToolExecutionPayload, ToolRuntimeHost } from "./tool-runtime.js";
import type {
  AgentConfig,
  ConversationTurn,
  HookDefinition,
  McpConfig,
  MemoryConfig,
  MemoryView,
  ModelItem,
  RuntimeOptions,
  SkillDefinition,
  SkillLoader,
  SkillResourceRequest,
  ThreadMemory,
  ToolDefinition,
} from "./types.js";

export interface PreparedConfig {
  value: Record<string, unknown>;
  callbackIds: string[];
}

function outputString(value: unknown): string {
  return typeof value === "string" ? value : JSON.stringify(value);
}

function normalizeTool(
  tool: ToolDefinition<any>,
  registry: CallbackRegistry,
  lifetime: string[],
  host: ToolRuntimeHost,
): Record<string, unknown> {
  const controllers = new Map<string, AbortController>();
  const executeCallbackId = registry.registerInvoke(async (rawPayload) => {
    const payload = rawPayload as ToolExecutionPayload;
    const controller = new AbortController();
    controllers.set(payload.executionId, controller);
    try {
      const argumentsValue = payload.argumentsJson.trim() === "" ? {} : JSON.parse(payload.argumentsJson);
      const output = await runToolCallback(
        host,
        payload,
        controller.signal,
        async (context) => await tool.execute(argumentsValue, context),
      );
      return { output: outputString(output) };
    } finally {
      controllers.delete(payload.executionId);
    }
  }, lifetime);
  const cancelCallbackId = registry.registerNotify((rawPayload) => {
    const payload = rawPayload as { executionId: string; reason?: string };
    controllers.get(payload.executionId)?.abort(payload.reason);
  }, lifetime);
  return {
    ...toBridgeValue(tool) as Record<string, unknown>,
    execute_callback_id: executeCallbackId,
    cancel_callback_id: cancelCallbackId,
  };
}

function normalizeThreadMemory(memory: ThreadMemory, registry: CallbackRegistry, lifetime: string[]): Record<string, unknown> {
  const loadCallbackId = registry.registerInvoke(async (rawPayload) => {
    const payload = rawPayload as { query: ModelItem[] };
    return await memory.load(payload.query);
  }, lifetime);
  const commitCallbackId = registry.registerInvoke(async (rawPayload) => {
    await memory.commit(rawPayload as ConversationTurn);
    return null;
  }, lifetime);
  const value: Record<string, unknown> = {
    retention: memory.retention ?? "interrupted",
    load_callback_id: loadCallbackId,
    commit_callback_id: commitCallbackId,
  };
  if (memory.close !== undefined) {
    value.close_callback_id = registry.registerNotify(() => memory.close?.(), lifetime);
  }
  return value;
}

export function normalizeMemory(
  memory: MemoryConfig,
  registry: CallbackRegistry,
  lifetime: string[],
): Record<string, unknown> {
  const value = toBridgeValue(memory) as Record<string, unknown>;
  if (memory.type === "custom") {
    value.open_callback_id = registry.registerInvoke(async (rawPayload) => {
      const payload = rawPayload as { threadId: string };
      return normalizeThreadMemory(await memory.open(payload.threadId), registry, lifetime);
    }, lifetime);
  } else if (memory.type === "vector") {
    value.add_callback_id = registry.registerInvoke(async (rawPayload) => {
      const payload = rawPayload as { threadId: string; items: ModelItem[] };
      await memory.store.add(payload.threadId, payload.items);
      return null;
    }, lifetime);
    value.search_callback_id = registry.registerInvoke(async (rawPayload) => {
      const payload = rawPayload as { threadId: string; query: string; topK: number };
      return await memory.store.search(payload.threadId, payload.query, payload.topK);
    }, lifetime);
    delete value.store;
  }
  return value;
}

function normalizeSkillDefinition(
  definition: SkillDefinition,
  id: string,
  registry: CallbackRegistry,
  lifetime: string[],
  host: ToolRuntimeHost,
): Record<string, unknown> {
  const value = toBridgeValue(definition) as Record<string, unknown>;
  value.descriptor = toBridgeValue(definition.descriptor ?? {
    id,
    description: definition.description ?? id,
  });
  if (definition.tools !== undefined) {
    value.tools = definition.tools.map((tool) => normalizeTool(tool, registry, lifetime, host));
  }
  if (definition.readResource !== undefined) {
    value.resource_callback_id = registry.registerInvoke(async (request) => {
      return await definition.readResource?.(request as SkillResourceRequest);
    }, lifetime);
  }
  return value;
}

function normalizeSkillLoader(
  loader: SkillLoader,
  registry: CallbackRegistry,
  lifetime: string[],
  host: ToolRuntimeHost,
): Record<string, unknown> {
  return {
    type: "loader",
    discover_callback_id: registry.registerInvoke(async () => await loader.discover(), lifetime),
    load_callback_id: registry.registerInvoke(async (rawPayload) => {
      const payload = rawPayload as { id: string };
      return normalizeSkillDefinition(await loader.load(payload.id), payload.id, registry, lifetime, host);
    }, lifetime),
  };
}

function normalizeMcp(config: McpConfig, registry: CallbackRegistry, lifetime: string[]): Record<string, unknown> {
  const value = toBridgeValue(config) as Record<string, unknown>;
  if (config.type === "gateway") {
    value.list_tools_callback_id = registry.registerInvoke(async () => await config.listTools(), lifetime);
    value.call_tool_callback_id = registry.registerInvoke(async (rawPayload) => {
      const payload = rawPayload as { name: string; argumentsJson: string };
      const argumentsValue = payload.argumentsJson.trim() === "" ? {} : JSON.parse(payload.argumentsJson);
      return { output: outputString(await config.callTool(payload.name, argumentsValue)) };
    }, lifetime);
  }
  return value;
}

function normalizeHook(hook: HookDefinition, registry: CallbackRegistry, lifetime: string[]): Record<string, unknown> {
  const value: Record<string, unknown> = {};
  if (hook.beforeModel !== undefined) value.before_model_callback_id = registry.registerInvoke(
    (payload) => hook.beforeModel?.(payload as Record<string, import("./types.js").JsonValue>) ?? null,
    lifetime,
  );
  if (hook.afterModelEvent !== undefined) value.after_model_event_callback_id = registry.registerInvoke(
    (payload) => hook.afterModelEvent?.(payload as Record<string, import("./types.js").JsonValue>) ?? null,
    lifetime,
  );
  if (hook.beforeTool !== undefined) value.before_tool_callback_id = registry.registerInvoke(
    (payload) => hook.beforeTool?.(payload as Record<string, import("./types.js").JsonValue>) ?? null,
    lifetime,
  );
  if (hook.afterTool !== undefined) value.after_tool_callback_id = registry.registerInvoke(
    (payload) => hook.afterTool?.(payload as Record<string, import("./types.js").JsonValue>) ?? null,
    lifetime,
  );
  return value;
}

export function prepareAgentConfig(
  config: AgentConfig,
  registry: CallbackRegistry,
  host: ToolRuntimeHost,
): PreparedConfig {
  const callbackIds: string[] = [];
  const value = toBridgeValue(config) as Record<string, unknown>;
  const model = value.model;
  const normalizeProviderType = (provider: unknown): void => {
    if (provider !== null && typeof provider === "object" && (provider as Record<string, unknown>).type === "openai-responses") {
      (provider as Record<string, unknown>).type = "openai_responses";
    }
  };
  if (Array.isArray(model)) model.forEach(normalizeProviderType);
  else normalizeProviderType(model);

  if (Array.isArray(config.instructions)) {
    value.instructions = config.instructions.map((segment) => {
      if (segment.type === "static") return toBridgeValue(segment);
      return {
        type: "dynamic",
        callback_id: registry.registerInvoke(async () => await segment.resolve(), callbackIds),
      };
    });
  }
  if (config.tools !== undefined) {
    value.tools = config.tools.map((tool) => normalizeTool(tool, registry, callbackIds, host));
  }
  if (config.memory !== undefined) value.memory = normalizeMemory(config.memory, registry, callbackIds);
  if (config.skills !== undefined) {
    value.skills = {
      sources: config.skills.sources.map((source) => source.type === "directory"
        ? toBridgeValue(source)
        : normalizeSkillLoader(source.loader, registry, callbackIds, host)),
      use: config.skills.use ?? [],
    };
  }
  if (config.mcp !== undefined) value.mcp = config.mcp.map((entry) => normalizeMcp(entry, registry, callbackIds));
  if (config.hooks !== undefined) value.hooks = config.hooks.map((hook) => normalizeHook(hook, registry, callbackIds));
  if (config.listeners !== undefined) {
    value.listeners = config.listeners.map((listener) => ({
      callback_id: registry.registerNotify((payload) => listener(payload as Record<string, import("./types.js").JsonValue>), callbackIds),
    }));
  }
  if (config.clientActions !== undefined) {
    value.client_actions = config.clientActions.map((handler) => ({
      callback_id: registry.registerInvoke((payload) => handler(payload as ModelItem), callbackIds),
    }));
  }
  if (config.termination !== undefined && "decide" in config.termination) {
    value.termination = {
      callback_id: registry.registerInvoke((payload) => config.termination !== undefined && "decide" in config.termination
        ? config.termination.decide(payload as import("./types.js").AgentStateSnapshot)
        : { action: "continue" }, callbackIds),
    };
  }
  if (config.errorPolicy?.type === "custom") {
    value.error_policy = {
      type: "custom",
      callback_id: registry.registerInvoke((payload) => {
        const context = payload as {
          error: import("./types.js").AgentError;
          state: import("./types.js").AgentStateSnapshot;
        };
        return config.errorPolicy?.type === "custom"
          ? config.errorPolicy.decide(context.error, context.state)
          : { action: "propagate" };
      }, callbackIds),
    };
  }
  return { value, callbackIds };
}

export function prepareRuntimeConfig(options: RuntimeOptions, registry: CallbackRegistry): PreparedConfig {
  const callbackIds: string[] = [];
  const value = toBridgeValue(options) as Record<string, unknown>;
  if (options.defaultMemory !== undefined) {
    value.default_memory = normalizeMemory(options.defaultMemory, registry, callbackIds);
  }
  return { value, callbackIds };
}
