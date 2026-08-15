import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

const exec = promisify(execFile);
const packDirectory = process.argv[2];
const npmExecutable = process.env.KOAKS_NPM_EXECUTABLE ?? "npm";
const tarballs = (await readdir(packDirectory)).filter((name) => name.endsWith(".tgz"));
assert.equal(tarballs.length, 1, `expected one package tarball, found ${tarballs.length}`);
const tarball = join(packDirectory, tarballs[0]);
const consumer = await mkdtemp(join(tmpdir(), "koaks-node-consumer-"));

try {
  await writeFile(join(consumer, "package.json"), JSON.stringify({ private: true, type: "module" }));
  await exec(npmExecutable, ["install", "--ignore-scripts", "--no-audit", "--no-fund", tarball], { cwd: consumer });
  await writeFile(join(consumer, "consumer.ts"), `
    import {
      createRuntime,
      type AgentResult,
      type IpcMessage,
      type ModelProvider,
      type RunHandle,
      type ToolExecutionContext,
    } from "@koaks/node";
    const provider: ModelProvider = { type: "openai", apiKey: "test", model: "fixture" };
    const runtime = createRuntime({ maxConcurrency: 1 });
    const result: Promise<AgentResult> | undefined = undefined;
    async function useToolContext(context: ToolExecutionContext, handle: RunHandle, message: IpcMessage) {
      await context.runtime.resources.withResource("fixture", async () => undefined);
      await context.runtime.context.put([]);
      await context.runtime.ipc.reply(message, "ok");
      await handle.release();
      return runtime.ipc.request(context.runtime.runId, "approval", "allow", { timeoutMs: 100 });
    }
    void provider; void runtime; void result; void useToolContext;
  `);
  const tsc = new URL("./node_modules/typescript/bin/tsc", import.meta.url);
  await exec(process.execPath, [
    tsc.pathname,
    "--noEmit",
    "--strict",
    "--target", "ES2022",
    "--module", "NodeNext",
    "--moduleResolution", "NodeNext",
    join(consumer, "consumer.ts"),
  ], { cwd: consumer });
  await writeFile(join(consumer, "consumer.mjs"), `
    import assert from "node:assert/strict";
    import { createRuntime } from "@koaks/node";
    const runtime = createRuntime();
    await runtime.close();
    await assert.rejects(
      import("@koaks/node/internal/koaks-node-bridge.mjs"),
      (error) => error?.code === "ERR_PACKAGE_PATH_NOT_EXPORTED",
    );
  `);
  await exec(process.execPath, [join(consumer, "consumer.mjs")], { cwd: consumer });
} finally {
  await rm(consumer, { recursive: true, force: true });
}
