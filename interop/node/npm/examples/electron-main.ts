import { app, BrowserWindow, ipcMain } from "electron";
import { createRuntime, type HookExecutionContext, type RunHandle } from "@koaks/node";

type ApprovalDecision = "allow" | "deny";
type ToolCall = { id: string; name: string; arguments_json: string };
type ActiveRun = { handle: RunHandle; done: Promise<void> };

let mainWindow: BrowserWindow | undefined;
let acceptingRuns = true;
const activeRuns = new Map<string, ActiveRun>();
const pendingApprovals = new Map<string, (decision: ApprovalDecision) => void>();

function requestApproval(call: ToolCall, execution: HookExecutionContext): Promise<boolean> {
  const window = mainWindow;
  if (!window || window.isDestroyed()) {
    throw new Error("No window is available to review this tool call");
  }

  const approvalId = `${execution.runId}:${call.id}`;
  return new Promise<boolean>((resolve, reject) => {
    const cleanup = () => {
      pendingApprovals.delete(approvalId);
      execution.signal.removeEventListener("abort", abort);
    };
    const abort = () => {
      cleanup();
      reject(execution.signal.reason ?? new Error("Run cancelled while awaiting approval"));
    };

    pendingApprovals.set(approvalId, (decision) => {
      cleanup();
      resolve(decision === "allow");
    });
    execution.signal.addEventListener("abort", abort, { once: true });
    window.webContents.send("agent:approval-requested", {
      approvalId,
      runId: execution.runId,
      correlationId: execution.correlationId,
      call,
    });
  });
}

const runtime = createRuntime({ maxConcurrency: 4 });
const agent = await runtime.createAgent({
  id: "desktop-assistant",
  instructions: "Help with the current desktop workflow.",
  model: {
    type: "openai",
    apiKey: process.env.OPENAI_API_KEY!,
    model: "gpt-4.1-mini",
  },
  memory: { type: "window", maxMessages: 40 },
  tools: [{
    name: "write_note",
    description: "Write a note after the desktop operator approves the change",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
    },
    hasSideEffects: true,
    async execute({ text }) {
      return `Saved note: ${text}`;
    },
  }],
  hooks: [{
    async beforeTool(context, execution) {
      const call = context.call as ToolCall;
      if (call.name !== "write_note") return { action: "proceed" };
      const allowed = await requestApproval(call, execution);
      return allowed
        ? { action: "proceed" }
        : { action: "deny", reason: "Denied by desktop operator" };
    },
  }],
});

async function startRun(
  sender: Electron.WebContents,
  requestId: string,
  input: string,
  threadId: string,
): Promise<void> {
  if (!acceptingRuns) throw new Error("Application is shutting down");
  if (activeRuns.has(requestId)) throw new Error(`Duplicate requestId: ${requestId}`);

  const handle = await agent.spawn(input, { threadId, correlationId: requestId });
  const active: ActiveRun = { handle, done: Promise.resolve() };
  activeRuns.set(requestId, active);
  active.done = (async () => {
    try {
      for await (const envelope of handle.events()) {
        if (!sender.isDestroyed()) sender.send("agent:event", requestId, envelope);
      }
      const result = await handle.result();
      if (!sender.isDestroyed()) sender.send("agent:result", requestId, result);
    } catch (error) {
      if (!sender.isDestroyed()) {
        sender.send("agent:error", requestId, error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (activeRuns.get(requestId) === active) activeRuns.delete(requestId);
      await handle.release();
    }
  })();
  await active.done;
}

ipcMain.on("agent:start", (event, requestId: string, input: string, threadId: string) => {
  void startRun(event.sender, requestId, input, threadId).catch((error) => {
    if (!event.sender.isDestroyed()) {
      event.sender.send("agent:error", requestId, error instanceof Error ? error.message : String(error));
    }
  });
});

ipcMain.handle("agent:cancel", async (_event, requestId: string) => {
  const active = activeRuns.get(requestId);
  if (!active) return false;
  await active.handle.cancel("Cancelled by desktop operator");
  return true;
});

ipcMain.handle("agent:approval", (_event, approvalId: string, decision: ApprovalDecision) => {
  const resolve = pendingApprovals.get(approvalId);
  if (!resolve) return false;
  resolve(decision);
  return true;
});

await app.whenReady();
mainWindow = new BrowserWindow({
  webPreferences: {
    contextIsolation: true,
    sandbox: true,
    nodeIntegration: false,
  },
});

let shutdownComplete = false;
let shutdownTask: Promise<void> | undefined;

function shutdown(): Promise<void> {
  if (shutdownTask) return shutdownTask;
  shutdownTask = (async () => {
    acceptingRuns = false;
    for (const resolve of pendingApprovals.values()) resolve("deny");

    const runs = [...activeRuns.values()];
    await Promise.allSettled(runs.map(({ handle }) => handle.cancel("Application shutdown")));
    await Promise.allSettled(runs.map(({ done }) => done));
    await agent.close();
    await runtime.close();
  })();
  return shutdownTask;
}

app.on("before-quit", (event) => {
  if (shutdownComplete) return;
  event.preventDefault();
  void shutdown().finally(() => {
    shutdownComplete = true;
    app.quit();
  });
});
