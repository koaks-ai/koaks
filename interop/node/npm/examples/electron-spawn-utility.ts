import { app, ipcMain, utilityProcess, type WebContents } from "electron";
import { join } from "node:path";

await app.whenReady();
const agentProcess = utilityProcess.fork(join(import.meta.dirname, "electron-utility.js"), [], {
  env: { ...process.env, ELECTRON_RUN_AS_NODE: "1" },
});
const subscribers = new Map<string, WebContents>();

ipcMain.on("agent:start", (event, requestId: string, input: string, threadId: string) => {
  subscribers.set(requestId, event.sender);
  agentProcess.postMessage({ type: "run", requestId, threadId, input });
});

ipcMain.on("agent:cancel", (_event, requestId: string) => {
  agentProcess.postMessage({ type: "cancel", requestId });
});

agentProcess.on("message", (message: { type: string; requestId?: string }) => {
  const requestId = message.requestId;
  if (!requestId) return;
  const sender = subscribers.get(requestId);
  if (sender && !sender.isDestroyed()) sender.send("agent:message", message);
  if (message.type === "result" || message.type === "error") subscribers.delete(requestId);
});

let shutdownComplete = false;
let shutdownTask: Promise<void> | undefined;

function shutdownUtility(): Promise<void> {
  if (shutdownTask) return shutdownTask;
  shutdownTask = new Promise<void>((resolve) => {
    agentProcess.once("exit", () => resolve());
    agentProcess.postMessage({ type: "close" });
  });
  return shutdownTask;
}

app.on("before-quit", (event) => {
  if (shutdownComplete) return;
  event.preventDefault();
  void shutdownUtility().finally(() => {
    shutdownComplete = true;
    app.quit();
  });
});
