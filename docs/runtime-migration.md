# Runtime model and migration

## Messaging and step-hook migration

See [Agent messaging and step hooks](agent-messaging.md) for the new APIs.
Children now default to `ChildConversation.Isolated`; explicitly request
`Inherit` to retain shared parent memory. A parent cannot spawn a second active
child with the same AgentId. New sealed variants are `ModelItem.IncomingMessage`,
`ChildConversation.Isolated`, and `RunEventPayload.Message`. Run snapshots add
`summary` and `acceptingMessages`.

`RunHandle.send` and child `sendTo` automatically inject conversation input;
existing `AgentContext.send/receive/request/reply` retain manual programmatic
messaging semantics. Install `tools { childAgents() }` to expose child listing and
sending to the model. `hook { onStep { ... } }` edits memory at loop boundaries.

## Run model

Koaks models an immutable `Agent` definition, individual runs, shared conversations,
and collaboration between runs. `AgentRuntime` is the application entry point;
`RunHandle` controls one execution and `RunSnapshot` describes it. An `AgentContext`
binds tool operations to the current run. Conversation identity remains `ThreadId`,
including the `thread` argument and the `MemoryProvider.open(ThreadId)` contract.

## Public API migration

This is a breaking source and binary API change. Old packages and aliases are removed.
Recompile Kotlin and Java consumers, and update the Node package and bridge together.

| Previous API | Replacement |
| --- | --- |
| `runtime.acb.AgentHandle` | `runtime.run.RunHandle` |
| `runtime.acb.AcbSnapshot` | `runtime.run.RunSnapshot` |
| `runtime.acb.LifecycleState` | `runtime.run.RunState` |
| `runtime.acb.RunId`, run event types | Same names in `runtime.run` |
| `runtime.resource.RuntimeContext` | `runtime.coordination.AgentContext` |
| `currentRuntimeContext()` | `currentAgentContext()` in `runtime.coordination` |
| `runtime.ipc` / `IpcHub` | `runtime.coordination` / `CoordinationFacade` |
| `RuntimeMessage`, `Mailbox` | `MessageEnvelope`, `Inbox` in `runtime.coordination` |
| `ipc.mailbox(runId)` | `coordination.inbox(runId)` |
| `runtime.resources.withResource(...)` | `runtime.coordination.withResource(...)` |
| `withRuntimeResource(...)` | `withSharedResource(...)` in `runtime.coordination` |
| `spawnChild`, message convenience functions | Same names in `runtime.coordination` |
| `Quota`, `quota`, child and supervision policies | `runtime.policy` |
| `TaskGraph`, `taskGraph` | `runtime.workflow` |
| `runtime.observe.*` | `runtime.observability.*` |
| `threadSnapshot(id)` / `ThreadSnapshot` | `conversationSnapshot(id)` / `ConversationSnapshot` |

`Acb` has become an internal `RunRecord`. Scheduling, run records, resource locks,
request correlation, and inbox registries are implementation details. Applications
use the facade, context, handles and snapshots.

Inside an agent tool, use the context directly:

```kotlin
import org.koaks.runtime.coordination.currentAgentContext

val ctx = requireNotNull(currentAgentContext())
val message = ctx.receive()
ctx.reply(message, "review complete")
val result = ctx.spawn(worker, "follow up").await()
ctx.publish("review.status", "completed", result.text)
ctx.withResource("review-log") { appendToLog(result.text) }
```

`ctx.send(...)` and `ctx.request(...)` take a destination `RunId`, message type,
payload and optional context items. Sender identity is supplied by the context.
Application code outside a run uses `runtime.coordination`, which accepts
`MessageEnvelope` objects and can use a null sender. Topic messages have no replay;
point-to-point inboxes remain FIFO. Message priority and deadline are metadata;
they do not reorder inbox delivery or enforce a timeout. Kotlin callers bound a
request with `withTimeout`; Node callers can pass `timeoutMs`.

## Java, JSON and Node

Java's `org.koaks.java.RunHandle` keeps its name and delegates to the new core
handle. Its `state` type is now `org.koaks.runtime.run.RunState`.

`KoaksWireJson.encodeAcbSnapshot` becomes `encodeRunSnapshot`, and
`encodeThreadSnapshot` becomes `encodeConversationSnapshot`. Snapshot fields,
state values and run-event payloads retain their existing wire representation.

Node exposes `AgentContext` through `ToolExecutionContext.runtime`; call
`context.runtime.send/receive/request/reply/publish/subscribe` directly. Application
operations move from `runtime.ipc` to `runtime.coordination`. Public types become
`MessageEnvelope`, `MessageSendOptions`, `MessageRequestOptions`,
`RuntimeMessageRequestOptions`, `MessageSubscriptionOptions`, `RunMessaging`, and
`RuntimeCoordination`; `ConversationSnapshot` replaces `ThreadSnapshot`.

The internal bridge routes are `tool.coordination.*`, `runtime.coordination.*`,
and `runtime.conversation_snapshot`. Existing message fields and error codes are
preserved, including the `ipc_reply_invalid` and `ipc_target_unavailable` codes.

## Implementation responsibilities

| Component | Responsibility |
| --- | --- |
| `RunManager` / `RunRegistry` | Runtime lifetime, agent registrations, run creation and retained records |
| `RunExecutor` | One execution's memory binding, admission, limits, result and finalization |
| `RunTreeCoordinator` | Descendant traversal, cancellation, joining and captured failures |
| `ConversationRegistry` | Shared memory binding and submission-order FIFO for each conversation |
| `ConcurrencyScheduler` / `InstanceActivityGate` | Admission permits and activity across execution branches |
| `RunRecord` / `RunControl` / `RunJournal` | State transitions, pause requests and bounded ordered event history |
| `CoordinationFacade` | Application-facing messaging and shared-resource access |
| `InboxRegistry` / `MessageRouter` | Inbox lifetime and point-to-point delivery |
| `RequestBroker` / `MessageIdGenerator` | Reply correlation, pending-request cleanup and message identity |
| `TopicBus` | Topic subscriptions and publication |
| `RetrySupervisor` / `TaskGraphExecutor` | Repeated attempts with backoff/circuit breaking and DAG composition |

Managed waits continue to release a run's permit only when all its branches wait.
The runtime retains child failure propagation, inherited/ephemeral conversations,
foreground cancellation, bounded event replay, and incremental memory writes.
`COMMITTING` protects finalization; it does not introduce a memory transaction.

State changes now pass through `RunRecord`'s transition guard, including failures
before admission and while paused. Terminal states cannot be overwritten by a
late completion or failure. Closing coordination also cancels pending requests
and prevents inboxes from reopening. A timed-out request still delivers a late
reply to its sender's inbox when a sender exists.

Regression coverage runs on JVM, Node.js and macOS Native. The npm package test
also installs the built package in a separate consumer and compiles the new public
TypeScript types; Kotlin examples and Java facade tests compile against the new API.
