import type { KoaksBridge } from "../internal/koaks-node-bridge.mjs";

export type Bridge = KoaksBridge;
export type CallbackHandler = (payload: unknown) => unknown | Promise<unknown>;

const PRESERVE_OBJECT_KEYS = new Set([
  "schema",
  "inputSchema",
  "input_schema",
  "parameters",
  "metadata",
  "headers",
]);

function snakeCase(value: string): string {
  return value.replace(/[A-Z]/g, (character) => `_${character.toLowerCase()}`);
}

function camelCase(value: string): string {
  return value.replace(/_([a-z])/g, (_, character: string) => character.toUpperCase());
}

function copyJson(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(copyJson);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, copyJson(child)]));
  }
  return value;
}

export function toBridgeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(toBridgeValue);
  if (value !== null && typeof value === "object") {
    const output: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value)) {
      if (child === undefined || typeof child === "function" || key === "signal" || key === "highWaterMark") continue;
      output[snakeCase(key)] = PRESERVE_OBJECT_KEYS.has(key) ? copyJson(child) : toBridgeValue(child);
    }
    return output;
  }
  return value;
}

export function fromBridgeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(fromBridgeValue);
  if (value !== null && typeof value === "object") {
    const output: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value)) {
      if (key === "dependencies") {
        output.dependencies = Object.fromEntries(
          Object.entries(child as Record<string, unknown>).map(([id, result]) => [id, fromBridgeValue(result)]),
        );
      } else {
        output[camelCase(key)] = PRESERVE_OBJECT_KEYS.has(key) ? copyJson(child) : fromBridgeValue(child);
      }
    }
    return output;
  }
  return value;
}

export class KoaksError extends Error {
  readonly code: string;

  constructor(code: string, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "KoaksError";
    this.code = code;
  }
}

export class KoaksConfigError extends KoaksError {
  constructor(message: string) {
    super("configuration_error", message);
    this.name = "KoaksConfigError";
  }
}

export class KoaksCancelledError extends KoaksError {
  constructor(message = "Koaks operation was cancelled") {
    super("cancelled", message);
    this.name = "KoaksCancelledError";
  }
}

export class KoaksBridgeError extends KoaksError {
  readonly bridgeStack: string | undefined;

  constructor(code: string, message: string, bridgeStack?: string) {
    super(code, message);
    this.name = "KoaksBridgeError";
    this.bridgeStack = bridgeStack;
  }
}

interface BridgeEnvelope {
  ok: boolean;
  value?: unknown;
  error?: { type?: string; message?: string; stack?: string };
}

export function errorFromBridge(error: { type?: string; message?: string; stack?: string }): KoaksError {
  const code = error.type ?? "bridge_error";
  const message = error.message ?? "Unknown Koaks bridge error";
  if (code === "cancelled") return new KoaksCancelledError(message);
  if (code === "configuration_error") return new KoaksConfigError(message);
  return new KoaksBridgeError(code, message, error.stack);
}

export class BridgeClient {
  constructor(private readonly bridge: Bridge) {}

  async request<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    let raw: string;
    try {
      raw = await this.bridge.request(method, JSON.stringify(toBridgeValue(params)));
    } catch (cause) {
      if (cause instanceof KoaksError) throw cause;
      const message = cause instanceof Error ? cause.message : String(cause);
      if (/cancel/i.test(message)) throw new KoaksCancelledError(message);
      throw new KoaksBridgeError("bridge_rejection", message);
    }
    const envelope = JSON.parse(raw) as BridgeEnvelope;
    if (!envelope.ok) throw errorFromBridge(envelope.error ?? {});
    return fromBridgeValue(envelope.value) as T;
  }
}

export class CallbackRegistry {
  private sequence = 0;
  private readonly invokeHandlers = new Map<string, CallbackHandler>();
  private readonly notifyHandlers = new Map<string, CallbackHandler>();

  readonly invoke = async (id: string, payloadJson: string): Promise<string> => {
    const handler = this.invokeHandlers.get(id);
    if (handler === undefined) throw new KoaksBridgeError("callback_not_found", `Unknown callback '${id}'`);
    const payload = fromBridgeValue(JSON.parse(payloadJson)) as unknown;
    const result = await handler(payload);
    return JSON.stringify(toBridgeValue(result ?? null));
  };

  readonly notify = (id: string, payloadJson: string): void => {
    const handler = this.notifyHandlers.get(id);
    if (handler === undefined) return;
    try {
      void handler(fromBridgeValue(JSON.parse(payloadJson)));
    } catch {
      // Observational listeners cannot block or fail the agent loop.
    }
  };

  registerInvoke(handler: CallbackHandler, lifetime?: string[]): string {
    const id = `invoke-${++this.sequence}`;
    this.invokeHandlers.set(id, handler);
    lifetime?.push(id);
    return id;
  }

  registerNotify(handler: CallbackHandler, lifetime?: string[]): string {
    const id = `notify-${++this.sequence}`;
    this.notifyHandlers.set(id, handler);
    lifetime?.push(id);
    return id;
  }

  release(ids: Iterable<string>): void {
    for (const id of ids) {
      this.invokeHandlers.delete(id);
      this.notifyHandlers.delete(id);
    }
  }
}

type NextWaiter<T> = { resolve: (result: IteratorResult<T>) => void; reject: (error: unknown) => void };

export class BoundedAsyncIterable<T> implements AsyncIterableIterator<T> {
  private readonly values: T[] = [];
  private readonly nextWaiters: NextWaiter<T>[] = [];
  private readonly spaceWaiters: Array<() => void> = [];
  private terminalError: unknown;
  private completed = false;
  private cancelled = false;
  private iteratorClaimed = false;

  constructor(
    private readonly highWaterMark: number,
    private readonly onCancel: () => void | Promise<void>,
  ) {
    if (!Number.isInteger(highWaterMark) || highWaterMark < 1) {
      throw new KoaksConfigError("highWaterMark must be a positive integer");
    }
  }

  [Symbol.asyncIterator](): AsyncIterableIterator<T> {
    if (this.iteratorClaimed) throw new KoaksError("stream_already_consumed", "Koaks streams support one consumer");
    this.iteratorClaimed = true;
    return this;
  }

  async push(value: T): Promise<void> {
    while (!this.completed && !this.cancelled && this.values.length >= this.highWaterMark) {
      await new Promise<void>((resolve) => this.spaceWaiters.push(resolve));
    }
    if (this.completed || this.cancelled) return;
    const waiter = this.nextWaiters.shift();
    if (waiter !== undefined) waiter.resolve({ done: false, value });
    else this.values.push(value);
  }

  complete(): void {
    if (this.completed) return;
    this.completed = true;
    this.wakeProducers();
    if (this.values.length === 0) this.finishConsumers();
  }

  fail(error: unknown): void {
    if (this.completed) return;
    this.terminalError = error;
    this.completed = true;
    this.values.length = 0;
    this.wakeProducers();
    this.finishConsumers();
  }

  next(): Promise<IteratorResult<T>> {
    const value = this.values.shift();
    if (value !== undefined) {
      this.spaceWaiters.shift()?.();
      if (this.completed && this.values.length === 0) this.finishConsumers();
      return Promise.resolve({ done: false, value });
    }
    if (this.terminalError !== undefined) return Promise.reject(this.terminalError);
    if (this.completed || this.cancelled) return Promise.resolve({ done: true, value: undefined });
    return new Promise<IteratorResult<T>>((resolve, reject) => this.nextWaiters.push({ resolve, reject }));
  }

  async return(): Promise<IteratorResult<T>> {
    if (!this.cancelled) {
      this.cancelled = true;
      this.completed = true;
      this.values.length = 0;
      this.wakeProducers();
      this.finishConsumers();
      await this.onCancel();
    }
    return { done: true, value: undefined };
  }

  async throw(error: unknown): Promise<IteratorResult<T>> {
    this.fail(error);
    await this.return();
    throw error;
  }

  private wakeProducers(): void {
    for (const resolve of this.spaceWaiters.splice(0)) resolve();
  }

  private finishConsumers(): void {
    const error = this.terminalError;
    for (const waiter of this.nextWaiters.splice(0)) {
      if (error !== undefined) waiter.reject(error);
      else waiter.resolve({ done: true, value: undefined });
    }
  }
}

export function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted === true) {
    const reason = signal.reason;
    throw new KoaksCancelledError(reason instanceof Error ? reason.message : reason === undefined ? undefined : String(reason));
  }
}
