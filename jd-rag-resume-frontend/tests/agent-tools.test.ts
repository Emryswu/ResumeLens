import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { AGENT_TOOLS } from "../app/agent-steps.ts";

// The tool list comes from the backend sources, so adding a tool there without a name and icon here fails CI.
const backendDir = join(dirname(fileURLToPath(import.meta.url)), "../../jd-rag-resume-backend/src/main/java");

function javaFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? javaFiles(path) : name.endsWith(".java") ? [path] : [];
  });
}

/** Every class implementing AgentTool, with the literal its name() returns (null when it is not a plain literal). */
function backendTools(): { file: string; name: string | null }[] {
  return javaFiles(backendDir)
    .map((path) => ({ file: relative(backendDir, path), source: readFileSync(path, "utf8") }))
    .filter(({ source }) => /\bclass\s+\w+[^{]*\bimplements\b[^{]*\bAgentTool\b/.test(source))
    .map(({ file, source }) => ({
      file,
      name: /public\s+String\s+name\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"\s*;/.exec(source)?.[1] ?? null,
    }));
}

test("the backend tool list is found, and every name() is a literal this test can read", () => {
  const tools = backendTools();
  // Seven tools today; an empty list would mean the scan broke, not that the check passed.
  assert.ok(tools.length >= 7, `found only ${tools.length} AgentTool classes under ${backendDir}`);
  for (const tool of tools) assert.ok(tool.name, `${tool.file}: name() must return a string literal`);
});

test("every tool the backend registers has a Chinese name and an icon", () => {
  for (const { file, name } of backendTools()) {
    const meta = AGENT_TOOLS[name ?? ""];
    assert.ok(meta, `${name} (${file}) is missing from AGENT_TOOLS in app/agent-steps.ts`);
    assert.match(meta.label, /\p{Script=Han}/u, `${name} needs a Chinese name`);
    assert.ok(meta.icon, `${name} needs an icon`);
  }
});

test("the mapping table lists no tool the backend no longer has", () => {
  const names = new Set(backendTools().map((tool) => tool.name));
  assert.deepEqual(Object.keys(AGENT_TOOLS).filter((name) => !names.has(name)), []);
});
