import { app, utilityProcess } from "electron";
import { join } from "node:path";

await app.whenReady();
const agentProcess = utilityProcess.fork(join(import.meta.dirname, "electron-utility.js"), [], {
  env: { ...process.env, ELECTRON_RUN_AS_NODE: "1" },
});

agentProcess.postMessage({ type: "run", requestId: "request-1", threadId: "chat-1", input: "Hello" });
agentProcess.on("message", (message) => {
  console.log("Agent utility response", message);
});

app.on("before-quit", () => {
  agentProcess.postMessage({ type: "close" });
});
