import { spawn } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import path from "node:path";

const frontend = process.cwd();
const backend = path.resolve(frontend, "../jd-rag-resume-backend");
const output = path.join(frontend, "output/playwright", `run-${Date.now()}`);
await mkdir(output, { recursive: true });
const ready = path.join(output, "backend.json");
const quote = (value) => `'${value.replaceAll("'", "'\\''")}'`;
const wslPath = (value) => value.replace(/^([A-Za-z]):/, (_, drive) => `/mnt/${drive.toLowerCase()}`).replaceAll("\\", "/");
const args = ["-B", "--no-transfer-progress", "-Dtest=AgentBrowserFixtureTests", `-Dagent.browser.fixture=${process.platform === "win32" ? wslPath(ready) : ready}`, "test"];
if (process.env.MAVEN_OFFLINE) args.unshift("-o");
const fixture = process.platform === "win32"
  ? spawn("wsl", ["-e", "bash", "-lc", `cd ${quote(wslPath(backend))} && mvn ${args.map(quote).join(" ")}`])
  : spawn("./mvnw", args, { cwd: backend });
const log = createWriteStream(path.join(output, "backend.log"));
fixture.stdout.pipe(log); fixture.stderr.pipe(log);
let fixtureExit;
fixture.on("exit", (code) => { fixtureExit = code; });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
try {
  const deadline = Date.now() + 180_000;
  let config;
  while (!config && Date.now() < deadline) {
    if (fixtureExit !== undefined) throw new Error(`Backend fixture exited ${fixtureExit}; inspect ${output}`);
    try { config = JSON.parse(await readFile(ready, "utf8")); } catch { await sleep(300); }
  }
  if (!config) throw new Error(`Backend fixture did not become ready; inspect ${output}`);
  const runner = spawn(process.execPath, ["node_modules/@playwright/test/cli.js", "test", ...process.argv.slice(2)], {
    cwd: frontend, stdio: "inherit", env: { ...process.env, BACKEND_API_URL: `http://127.0.0.1:${config.port}` },
  });
  process.exitCode = await new Promise((resolve) => runner.on("exit", (code) => resolve(code ?? 1)));
} finally {
  await writeFile(`${ready}.stop`, "stop");
  const deadline = Date.now() + 15_000;
  while (fixtureExit === undefined && Date.now() < deadline) await sleep(200);
  if (fixtureExit === undefined) fixture.kill();
  if (fixtureExit !== undefined && fixtureExit !== 0) process.exitCode = 1;
  log.end();
}
