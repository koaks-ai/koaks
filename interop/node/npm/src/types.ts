export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonSchema = Record<string, JsonValue>;

export interface Usage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  cachedInputTokens: number;
  reasoningOutputTokens: number;
}

export type ContentPart =
  | { type: "text"; text: string }
  | { type: "image"; url?: string; base64?: string }
  | { type: "audio"; url?: string; base64?: string; format: string };

export type Annotation =
  | { type: "url_citation"; url: string; title?: string; startIndex?: number; endIndex?: number }
  | { type: "file_citation"; fileId: string; filename?: string; startIndex?: number; endIndex?: number }
  | { type: "generic"; kind: string; payload: string };

export interface ProviderScopedId {
  providerId: string;
  raw: string;
}

interface ModelItemBase {
  ref?: string;
  nativeId?: ProviderScopedId;
}

export type ModelItem =
  | (ModelItemBase & {
      type: "message";
      role: "system" | "user" | "assistant" | "tool";
      content: ContentPart[];
      refusal?: string;
      annotations?: Annotation[];
    })
  | (ModelItemBase & {
      type: "tool_call";
      name: string;
      argumentsJson: string;
      nativeItemId?: ProviderScopedId;
    })
  | (ModelItemBase & { type: "tool_result"; callRef: string; output: string; isError?: boolean })
  | (ModelItemBase & { type: "reasoning_summary"; text: string })
  | (ModelItemBase & {
      type: "provider_item";
      providerId: string;
      kind: string;
      displayText: string;
      replay: "required" | "preferred" | "optional";
      payloadBase64: string;
    });

export interface ProviderCheckpoint {
  providerId: string;
  codecVersion: number;
  basis: { itemCount: number; digest: string };
  scope: "in_run" | "cross_turn";
  payloadBase64: string;
  expiresAtEpochMs?: number;
}

export type AgentError =
  | { type: "model_error"; message: string; retriable: boolean; cause?: string }
  | { type: "tool_error"; message: string; toolName: string; retriable: boolean; cause?: string }
  | { type: "parse_error"; message: string; raw: string; cause?: string }
  | { type: "tool_not_found"; message: string; toolName: string; cause?: string }
  | { type: "skill_error"; message: string; skillId?: string; stage: string; cause?: string }
  | { type: "preparation_error"; message: string; component: string; cause?: string }
  | { type: "timeout"; message: string; stage: string; elapsedMs: number; cause?: string }
  | { type: "unknown_error"; message: string; cause?: string };

export type AgentResult =
  | { status: "completed"; text: string; message: ModelItem; usage: Usage }
  | { status: "incomplete"; text: string; message: ModelItem; usage: Usage; reason: Record<string, JsonValue> }
  | { status: "terminated"; text: string; message: ModelItem; usage: Usage; reason: Record<string, JsonValue> }
  | { status: "failed"; text: string; message: ModelItem; usage: Usage; error: AgentError };

export type AgentEvent =
  | { type: "text_delta"; text: string }
  | { type: "reasoning_delta"; text: string }
  | { type: "tool_call_requested"; call: ToolCall }
  | { type: "tool_result"; callId: string; output: string; isError: boolean }
  | { type: "step_completed"; step: number }
  | { type: "completed"; message: ModelItem; usage: Usage }
  | { type: "incomplete"; message: ModelItem; usage: Usage; reason: Record<string, JsonValue> }
  | { type: "terminated"; message: ModelItem; usage: Usage; reason: Record<string, JsonValue> }
  | { type: "failed"; error: AgentError; usage: Usage };

export interface ToolCall {
  id: string;
  name: string;
  argumentsJson: string;
  nativeId?: ProviderScopedId;
  nativeItemId?: ProviderScopedId;
}

export interface ModelCapabilities {
  parallelToolCalls?: boolean;
  vision?: boolean;
  jsonObject?: boolean;
  jsonSchema?: boolean;
  assistantPrefill?: boolean;
}

interface ProviderBase {
  baseUrl?: string;
  apiKey: string;
  model: string;
  streamIdleTimeoutMs?: number;
  capabilities?: ModelCapabilities;
}

export interface OpenAIProvider extends ProviderBase {
  type: "openai";
  temperature?: number;
  maxCompletionTokens?: number;
  topP?: number;
  stop?: string[];
  presencePenalty?: number;
  frequencyPenalty?: number;
  reasoningEffort?: string;
}

export interface OpenAIResponsesProvider extends ProviderBase {
  type: "openai-responses";
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  reasoning?: Record<string, JsonValue>;
  truncation?: string;
  background?: boolean;
  backgroundPollIntervalMs?: number;
  stateMode?: "replayable" | "server_stored" | "conversation";
  persistCheckpoint?: boolean;
  include?: string[];
  serverTools?: Array<
    | { type: "web_search"; searchContextSize?: string }
    | { type: "file_search"; vectorStoreIds: string[] }
    | { type: "code_interpreter"; container?: string }
  >;
}

export interface QwenProvider extends ProviderBase {
  type: "qwen";
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  stop?: string[];
  presencePenalty?: number;
  frequencyPenalty?: number;
  enableThinking?: boolean;
}

export interface AnthropicProvider extends ProviderBase {
  type: "anthropic";
  maxTokens?: number;
  temperature?: number;
  topP?: number;
  topK?: number;
  stopSequences?: string[];
  thinking?: Record<string, JsonValue>;
  anthropicVersion?: string;
}

export interface OllamaProvider extends Omit<ProviderBase, "apiKey"> {
  type: "ollama";
  baseUrl: string;
  apiKey?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  stop?: string[];
  think?: boolean;
}

export type ModelProvider = OpenAIProvider | OpenAIResponsesProvider | QwenProvider | AnthropicProvider | OllamaProvider;
export type ModelSelection = ModelProvider | readonly [ModelProvider, ...ModelProvider[]];

export interface ToolExecutionContext {
  readonly executionId: string;
  readonly signal: AbortSignal;
  readonly runtime: ToolRuntimeContext;
}

export interface ToolRuntimeContext {
  readonly runId: string;
  readonly agentId: string;
  readonly threadId?: string;
  readonly turnId?: string;
  readonly resources: ToolResources;
  readonly context: ToolContextStore;
  readonly ipc: ToolIpc;
  spawnChild(agent: KoaksAgent, input: string, options?: ChildSpawnOptions): Promise<RunHandle>;
}

export interface ToolResources {
  withResource<T>(
    id: string,
    block: () => T | Promise<T>,
    options?: { mode?: "read" | "write" },
  ): Promise<T>;
}

export interface ToolContextStore {
  put(items: ModelItem[], scope?: "private" | "task" | "global"): Promise<string>;
  delta(parentRef: string, items: ModelItem[], scope?: "private" | "task" | "global"): Promise<string>;
  resolve(ref: string): Promise<ModelItem[]>;
}

export interface ChildSpawnOptions {
  priority?: number;
  quota?: Quota;
  contextRefs?: string[];
  failurePolicy?: "propagate" | "capture";
  conversation?:
    | { type: "inherit" }
    | { type: "ephemeral" }
    | { type: "thread"; threadId: string };
}

export interface IpcMessage {
  readonly id: string;
  readonly senderRunId?: string;
  readonly receiverRunId?: string;
  readonly type: string;
  readonly payload: string;
  readonly contextRefs: string[];
  readonly priority: number;
  readonly deadlineEpochMs?: number;
}

export interface IpcSendOptions {
  contextRefs?: string[];
  priority?: number;
  deadlineEpochMs?: number;
}

export interface IpcRequestOptions extends IpcSendOptions {
  timeoutMs?: number;
}

export interface RuntimeIpcRequestOptions extends IpcRequestOptions {
  signal?: AbortSignal;
}

export interface IpcSubscriptionOptions {
  highWaterMark?: number;
  signal?: AbortSignal;
}

export interface ToolIpc {
  send(toRunId: string, type: string, payload?: string, options?: IpcSendOptions): Promise<void>;
  receive(): Promise<IpcMessage>;
  request(toRunId: string, type: string, payload?: string, options?: IpcRequestOptions): Promise<IpcMessage>;
  reply(message: IpcMessage, payload?: string, options?: Pick<IpcSendOptions, "contextRefs">): Promise<void>;
  publish(topic: string, type: string, payload?: string, options?: IpcSendOptions): Promise<void>;
  subscribe(topic: string, options?: IpcSubscriptionOptions): AsyncIterable<IpcMessage>;
}

export interface RuntimeIpc {
  send(toRunId: string, type: string, payload?: string, options?: IpcSendOptions): Promise<void>;
  request(toRunId: string, type: string, payload?: string, options?: RuntimeIpcRequestOptions): Promise<IpcMessage>;
  publish(topic: string, type: string, payload?: string, options?: IpcSendOptions): Promise<void>;
  subscribe(topic: string, options?: IpcSubscriptionOptions): AsyncIterable<IpcMessage>;
}

export interface ToolDefinition<TArguments = Record<string, unknown>> {
  name: string;
  description?: string;
  inputSchema: JsonSchema;
  returnDirectly?: boolean;
  hasSideEffects?: boolean;
  execute(argumentsValue: TArguments, context: ToolExecutionContext): string | JsonValue | Promise<string | JsonValue>;
}

export type TurnRetention = "interrupted" | "completed_only" | "interrupted_if_side_effects";

export interface MemoryView {
  transcript: ModelItem[];
  checkpoint?: ProviderCheckpoint;
}

export interface ConversationTurn {
  id: string;
  status: Record<string, JsonValue>;
  items: ModelItem[];
  checkpoint?: ProviderCheckpoint;
  usage: Usage;
}

export interface ThreadMemory {
  retention?: TurnRetention;
  load(query: ModelItem[]): MemoryView | Promise<MemoryView>;
  commit(turn: ConversationTurn): void | Promise<void>;
  close?(): void;
}

export interface VectorStore {
  add(threadId: string, items: ModelItem[]): void | Promise<void>;
  search(threadId: string, query: string, topK: number): ModelItem[] | Promise<ModelItem[]>;
}

export type MemoryConfig =
  | { type: "none" }
  | { type: "window"; maxMessages?: number; retention?: TurnRetention }
  | { type: "custom"; id: string; open(threadId: string): ThreadMemory | Promise<ThreadMemory> }
  | { type: "vector"; id: string; store: VectorStore; topK?: number; retention?: TurnRetention }
  | {
      type: "summarizing";
      id: string;
      model: ModelProvider;
      maxTokens: number;
      keepRecentTurns?: number;
      retention?: TurnRetention;
    };

export interface SkillDescriptor {
  id: string;
  description: string;
  metadata?: Record<string, string>;
}

export interface SkillResourceRequest {
  path: string;
  line: number;
  column: number;
  maxLines: number;
  maxChars: number;
}

export interface SkillResource {
  path: string;
  content: string;
  firstLine: number;
  lastLine: number;
  totalLines: number;
  nextCursor?: { line: number; column: number };
}

export interface SkillDefinition {
  descriptor?: SkillDescriptor;
  description?: string;
  instructions: string;
  tools?: ToolDefinition<any>[];
  readResource?(request: SkillResourceRequest): SkillResource | Promise<SkillResource>;
}

export interface SkillLoader {
  discover(): SkillDescriptor[] | Promise<SkillDescriptor[]>;
  load(id: string): SkillDefinition | Promise<SkillDefinition>;
}

export interface SkillsConfig {
  sources: Array<{ type: "directory"; path: string } | { type: "loader"; loader: SkillLoader }>;
  use?: string[];
}

export interface McpTool {
  name: string;
  description?: string;
  inputSchema?: JsonSchema;
}

export type McpConfig =
  | { type: "http"; url: string; clientId?: number; headers?: Record<string, string> }
  | {
      type: "gateway";
      listTools(): McpTool[] | Promise<McpTool[]>;
      callTool(name: string, argumentsValue: unknown): string | JsonValue | Promise<string | JsonValue>;
    };

export type ModelEventDecision =
  | { action: "keep" }
  | { action: "drop" }
  | { action: "replace"; events: Record<string, JsonValue> | Array<Record<string, JsonValue>> };
export type ToolDecision =
  | { action: "proceed" }
  | { action: "deny"; reason: string }
  | { action: "replace"; call: ToolCall };

export interface HookDefinition {
  beforeModel?(context: Record<string, JsonValue>): Record<string, JsonValue> | null | Promise<Record<string, JsonValue> | null>;
  afterModelEvent?(context: Record<string, JsonValue>): ModelEventDecision | Promise<ModelEventDecision>;
  beforeTool?(context: Record<string, JsonValue>): ToolDecision | null | Promise<ToolDecision | null>;
  afterTool?(context: Record<string, JsonValue>): Record<string, JsonValue> | null | Promise<Record<string, JsonValue> | null>;
}

export type InstructionSegment =
  | { type: "static"; text: string }
  | { type: "dynamic"; resolve(): string | null | Promise<string | null> };

export interface AgentConfig {
  id: string;
  name?: string;
  instructions?: string | InstructionSegment[];
  model: ModelSelection;
  tools?: ToolDefinition<any>[];
  memory?: MemoryConfig;
  skills?: SkillsConfig;
  mcp?: McpConfig[];
  hooks?: HookDefinition[];
  listeners?: Array<(event: Record<string, JsonValue>) => void>;
  clientActions?: Array<(item: ModelItem) => ModelItem | null | Promise<ModelItem | null>>;
  termination?:
    | { maxSteps?: number; maxTokens?: number }
    | {
        decide(state: AgentStateSnapshot): TerminationDecision | Promise<TerminationDecision>;
      };
  runBudget?: { maxTotalSteps?: number; maxTotalTokens?: number };
  errorPolicy?:
    | { type: "propagate" }
    | { type: "retry_retriable"; maxRetries?: number; delayMs?: number }
    | { type: "substitute"; message: ModelItem }
    | {
        type: "custom";
        decide(error: AgentError, state: AgentStateSnapshot): ErrorRecovery | Promise<ErrorRecovery>;
      };
}

export interface AgentStateSnapshot {
  items: ModelItem[];
  instructions?: string;
  checkpoint?: ProviderCheckpoint;
  globalStep: number;
  localStep: number;
  usage: Usage;
  activeAgentName: string;
}

export type TerminationDecision =
  | { action: "continue" }
  | { action: "stop"; message: string };

export type ErrorRecovery =
  | { action: "propagate" }
  | { action: "retry"; delayMs?: number; maxRetries?: number }
  | { action: "substitute"; message: ModelItem };

export interface Quota {
  maxSteps?: number;
  maxToolCalls?: number;
  wallClockMs?: number;
}

export interface RunOptions {
  threadId?: string;
  priority?: number;
  quota?: Quota;
  contextRefs?: string[];
  signal?: AbortSignal;
}

export interface StreamOptions extends RunOptions {
  highWaterMark?: number;
}

export interface OutputSchema {
  name: string;
  schema: JsonSchema;
}

export type StructuredAgentResult<T> =
  | (Extract<AgentResult, { status: "completed" }> & { output: T })
  | Exclude<AgentResult, { status: "completed" }>;

export interface RunSnapshot {
  runId: string;
  agentId: string;
  agentName: string;
  threadId?: string;
  turnId?: string;
  state: "created" | "thread_queued" | "ready" | "running" | "waiting" | "suspended" | "committing" | "finished" | "failed" | "cancelled";
  priority: number;
  parent?: string;
  children: string[];
  acceptingChildren: boolean;
  usage: Usage;
  stepsCompleted: number;
  toolCalls: number;
  elapsedMs: number;
  error?: AgentError;
}

export interface ThreadSnapshot {
  id: string;
  memoryProviderId: string;
  participants: string[];
  activeTurn?: string;
  queuedTurns: string[];
}

export interface RuntimeMetrics {
  total: number;
  created: number;
  threadQueued: number;
  ready: number;
  running: number;
  waiting: number;
  suspended: number;
  committing: number;
  finished: number;
  failed: number;
  cancelled: number;
  totalTokens: number;
  totalSteps: number;
  totalToolCalls: number;
}

interface RuntimeRunEventBase {
  runId: string;
  agentId: string;
  threadId?: string;
  turnId?: string;
}

export type RuntimeEvent =
  | (RuntimeRunEventBase & { type: "spawned"; agentName: string; priority: number; parent?: string })
  | (RuntimeRunEventBase & { type: "running"; agentName: string })
  | (RuntimeRunEventBase & { type: "waiting" | "suspended" | "resumed" | "cancelled" })
  | (RuntimeRunEventBase & { type: "finished"; usage: Usage })
  | (RuntimeRunEventBase & { type: "incomplete"; reason: Record<string, JsonValue>; usage: Usage })
  | (RuntimeRunEventBase & { type: "terminated"; reason: Record<string, JsonValue> })
  | (RuntimeRunEventBase & { type: "failed"; error: AgentError })
  | (RuntimeRunEventBase & { type: "side_effect_rollback" })
  | {
      type: "unhandled_child_failure";
      runId: string;
      agentId: string;
      parentRunId: string;
      error: AgentError;
    }
  | {
      type: "retrying";
      runId?: string;
      agentId: string;
      agentName: string;
      threadId?: string;
      turnId?: string;
      attempt: number;
      delayMs: number;
    }
  | {
      type: "circuit_open";
      runId?: string;
      agentId: string;
      agentName: string;
      threadId?: string;
      turnId?: string;
    };

export type ContextScope =
  | { type: "global" }
  | { type: "task" }
  | { type: "private"; ownerRunId: string };

export interface TaskDefinition {
  id: string;
  agent: KoaksAgent;
  input: string | ((dependencies: Record<string, AgentResult>) => string | Promise<string>);
  priority?: number;
  dependsOn?: string[];
}

export interface SupervisionPolicy {
  maxRetries?: number;
  initialBackoffMs?: number;
  backoffFactor?: number;
  maxBackoffMs?: number;
  retryOn?: "failed" | "not_completed";
  circuitBreaker?: { failureThreshold?: number; resetTimeoutMs?: number };
  recover?(attempt: number, last: AgentResult): string | Promise<string>;
}

export interface ReapOptions {
  olderThanMs?: number;
}

export interface RuntimeOptions {
  maxConcurrency?: number;
  defaultQuota?: Quota;
  defaultMemory?: MemoryConfig;
  highWaterMark?: number;
}

export interface RunHandle {
  readonly runId: string;
  readonly agentId: string;
  readonly threadId?: string;
  readonly turnId?: string;
  readonly parentRunId?: string;
  result(): Promise<AgentResult>;
  cancel(reason?: string): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  snapshot(): Promise<RunSnapshot>;
  updates(options?: StreamOptions): AsyncIterable<RunSnapshot>;
  release(): Promise<void>;
}

export interface SupervisedHandle {
  result(): Promise<AgentResult>;
  cancel(reason?: string): Promise<void>;
}

export interface KoaksAgent {
  readonly id: string;
  readonly name: string;
  prepare(): Promise<void>;
  run(input: string, options?: RunOptions): Promise<AgentResult>;
  runStructured<T>(input: string, output: OutputSchema, options?: RunOptions): Promise<StructuredAgentResult<T>>;
  stream(input: string, options?: StreamOptions): AsyncIterable<AgentEvent>;
  spawn(input: string, options?: RunOptions): Promise<RunHandle>;
  resume(threadId: string, options?: StreamOptions): AsyncIterable<AgentEvent>;
  resumeRun(threadId: string, options?: RunOptions): Promise<AgentResult>;
  close(): Promise<void>;
}

export interface KoaksRuntime {
  readonly ipc: RuntimeIpc;
  createAgent(config: AgentConfig): Promise<KoaksAgent>;
  replaceAgent(config: AgentConfig): Promise<KoaksAgent>;
  events(options?: StreamOptions): AsyncIterable<RuntimeEvent>;
  metrics(): Promise<RuntimeMetrics>;
  runs(): Promise<RunSnapshot[]>;
  snapshot(runId: string): Promise<RunSnapshot | undefined>;
  threadSnapshot(threadId: string): Promise<ThreadSnapshot | undefined>;
  putContext(items: ModelItem[], scope?: ContextScope): Promise<string>;
  deltaContext(parentRef: string, items: ModelItem[], scope?: ContextScope): Promise<string>;
  resolveContext(ref: string, requesterRunId?: string): Promise<ModelItem[]>;
  submit(tasks: TaskDefinition[]): Promise<Record<string, AgentResult>>;
  spawnSupervised(agent: KoaksAgent, input: string, policy?: SupervisionPolicy): Promise<SupervisedHandle>;
  reap(options?: ReapOptions): Promise<number>;
  close(): Promise<void>;
}
