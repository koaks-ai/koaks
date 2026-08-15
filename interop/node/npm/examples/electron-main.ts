import { app, BrowserWindow, ipcMain } from "electron";
import { createRuntime } from "@koaks/node";

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
    name: "request_approval",
    description: "Ask the desktop operator to approve a privileged action",
    inputSchema: {
      type: "object",
      properties: { summary: { type: "string" } },
      required: ["summary"],
    },
    async execute({ summary }, { runtime: toolRuntime }) {
      await toolRuntime.ipc.publish("desktop:approval", "approval_requested", summary);
      const decision = await toolRuntime.ipc.receive();
      return decision.payload;
    },
  }],
});

ipcMain.handle("agent:run", async (_event, input: string, threadId: string) => {
  return await agent.run(input, { threadId });
});

ipcMain.on("agent:stream", async (event, requestId: string, input: string, threadId: string) => {
  try {
    for await (const update of agent.stream(input, { threadId })) {
      event.sender.send("agent:event", requestId, update);
    }
  } catch (error) {
    event.sender.send("agent:error", requestId, error instanceof Error ? error.message : String(error));
  }
});

ipcMain.handle("agent:approval", async (_event, runId: string, decision: "allow" | "deny") => {
  await runtime.ipc.send(runId, "approval_result", decision);
});

await app.whenReady();
const mainWindow = new BrowserWindow({ webPreferences: { contextIsolation: true, sandbox: true } });
const approvals = runtime.ipc.subscribe("desktop:approval");
void (async () => {
  for await (const message of approvals) {
    mainWindow.webContents.send("agent:approval-requested", message.senderRunId, message.payload);
  }
})();

let shutdownComplete = false;
app.on("before-quit", (event) => {
  if (shutdownComplete) return;
  event.preventDefault();
  void agent.close()
    .then(() => runtime.close())
    .finally(() => {
      shutdownComplete = true;
      app.quit();
    });
});
