# Agent messaging and step hooks

`AgentId` identifies a reusable definition. `RunHandle` controls one execution.
Send user input to that execution with `handle.send("message")`, or retrieve its
handle using `runtime.handle(runId)`. `Accepted(messageId)` means the message was
queued, not that a model has processed it. Closed targets and full inboxes return
`Rejected(reason)`; `AgentRuntimeConfig.inboxCapacity` defaults to 64.

```kotlin
val handle = runtime.spawn(worker, "Review authentication", summary = "Review auth changes")
when (val receipt = handle.send("Also inspect token refresh races")) {
    is SendResult.Accepted -> println("Queued ${receipt.messageId}")
    is SendResult.Rejected -> println(receipt.reason)
}
val result = handle.await()
```

## Child agents

Register the optional built-in tools with `tools { childAgents() }` (import
`org.koaks.runtime.tools.childAgents`). The model receives:

- `list_children()`: direct children with `agentId`, `name`, task `summary`,
  `state` and `acceptingMessages`. One entry per AgentId shows the current run,
  or its latest retained finished run.
- `send_to_child(agentId, message)`: resolves the current active direct child
  and uses the same inbox delivery service as `RunHandle.send`.

Kotlin tools can also call `currentAgentContext()!!.listChildren()` and
`context.sendTo(AgentId("researcher"), "Check the tests")`. The parent identity
comes from the invocation, not from model-supplied arguments. Tools return a JSON
receipt and do not block for a reply.

Within one parent Run, an AgentId can have only one unfinished child execution.
Concurrent duplicate spawns fail. After the child finishes, the same definition
can be spawned again; AgentId addressing then selects that new execution. Retain
a RunHandle when an exact historical execution matters. Different parent runs
can concurrently use the same definition.

Set the Agent's `summary` for its general purpose. Override it per child with
`context.spawn(worker, input, summary = "Inspect the payment retry changes")`.

Children now default to `ChildConversation.Isolated`, which allocates a separate
ThreadId and uses the child's MemoryProvider or the Runtime default. To share the
parent transcript explicitly, choose `ChildConversation.Inherit`. The existing
`Thread(id)` and `Ephemeral` options remain available. Parent/child cancellation
and failure propagation are unchanged.

## Automatic memory injection

An internal per-run Hook consumes a fixed FIFO batch immediately before each
model request, then user step hooks run and memory is loaded. Each message is
stored as `ModelItem.IncomingMessage` with its original metadata. Providers
render it as ordinary input with a sender label; it never becomes a system
instruction. This is also supported by JSON, window/summary memory, transcript
repair and checkpoint validation.

New arrivals during a batch or a model call are handled at the next request.
Before normal completion, an atomic check either closes an empty inbox or keeps
the loop running to process pending messages. Structured output buffers its
candidate text until this check succeeds, so callers receive one final JSON.
Messages never restart a finished execution. Limits, cancellation, failure and
incomplete model responses can still end a run with pending input.

The RunJournal reports `RunEventPayload.Message` statuses `accepted`, `injected`
and `unprocessed`. Writes are acknowledged only after `ThreadMemory.append`
succeeds. A retry reuses the item's ref and relies on the MemoryProvider's
same-ref upsert contract. This is in-process delivery, not a durable message
queue. A successfully injected item may subsequently be filtered or summarized
by a custom MemoryProvider or user Hook.

Programmatic `AgentContext.send/receive/request/reply` and
`runtime.coordination` keep their existing separate inbox channels. They do not
automatically inject messages, and cannot steal input sent through Handle.send.
Topic subscriptions remain independent.

## Step lifecycle

The existing `Hook` interface now includes `onStep(StepHookContext)`:

```kotlin
hook {
    onStep { ctx ->
        if (ctx.stage == StepPhase.BeforeModelRequest) {
            ctx.memory.append(ModelItem.user("Current task-specific guidance"))
        }
        StepDecision.Continue
    }
    onModelCall {
        before { ctx -> ctx.request } // edit the request without persisting it
    }
}
```

The phases are `BeforeModelRequest`, `AfterModelResponse` (usage and steps updated,
before tool execution), and `AfterStep` (including completed tool results).
`ctx.phase` distinguishes normal calls from structured finalization; `retry`
identifies a normal-call recovery attempt. Before-model callbacks execute on each
attempt, so use stable item refs when repeated edits should replace each other.

`ctx.memory.load()` returns the provider view. `append(item)` adds or updates an
item using its ref, and is available only during that callback. Runtime counters
are immutable. Request-only transformations remain in `onModelRequest`.

Return `Continue`, `Stop(TerminationReason)` or `Suspend(reason)`. Suspend parks
the run's admission slot; `handle.resume()` continues after that callback without
repeating it. Hook failures end the run; injection write failures use the existing
error recovery policy. No step-after callback is promised after cancellation or
a failed model call.

Step/token policies apply to normal calls and structured finalization. Token
limits are checked after reported usage, before tools or another call; a single
call can exceed the remaining allowance. Optional
`ContextBudget(maxInputTokens, InputTokenCounter)` checks the final request after
request hooks. Supply a counter for the actual model, including instructions,
tools and output schema; there is no character-count fallback. A Hook can compact
the request first, but cannot bypass the final check. Runtime quotas, timeouts,
resource access and concurrency keep their existing enforcement.

## Java and Node

Java RunHandle supports `send(message)` and `sendAsync(message)`. The Agent
builder supports `summary`, `childAgentTools`, `hook`, `stepHook`, and a
`contextBudget` callback. Kotlin Hook implementations can be installed directly.

Node RunHandle exposes `await handle.send(message)`. Agent configuration accepts
`summary`, `childAgentTools: true`, and `contextBudget: { maxInputTokens, count }`.
Tool runtime contexts support `listChildren()` and `sendTo(agentId, message)`.

Node `hooks[].onStep` receives the current memory snapshot and returns ordered
appends plus an optional decision:

```typescript
hooks: [{
  onStep(ctx) {
    if (ctx.stage !== "before_model_request") return;
    return {
      append: [{ type: "message", ref: "guidance", role: "user",
                 content: [{ type: "text", text: "Check recent changes" }] }],
      decision: { action: "continue" }
    };
  }
}]
```

Public wire additions are `incoming_message`, message delivery journal events,
and snapshot `summary` / `accepting_messages`. Update exhaustive consumers of
ModelItem, RunEventPayload and ChildConversation for the new variants.
