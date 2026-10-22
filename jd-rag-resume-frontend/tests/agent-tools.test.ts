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

/**
 * Every class implementing AgentTool, directly or through an interface that extends it (DeferrableTool), with
 * the literal its name() returns (null when it is not a plain literal).
 */
function backendTools(): { file: string; name: string | null }[] {
  const sources = javaFiles(backendDir).map((path) => ({ file: relative(backendDir, path), source: readFileSync(path, "utf8") }));
  const toolInterfaces = ["AgentTool", ...sources.flatMap(({ source }) =>
    [...source.matchAll(/\binterface\s+(\w+)\s+extends\b[^{]*\bAgentTool\b/g)].map((match) => match[1]))];
  const implementsTool = new RegExp(`\\bclass\\s+\\w+[^{]*\\bimplements\\b[^{]*\\b(?:${toolInterfaces.join("|")})\\b`);
  return sources
    .filter(({ source }) => implementsTool.test(source))
    .map(({ file, source }) => ({
      file,
      name: /public\s+String\s+name\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"\s*;/.exec(source)?.[1] ?? null,
    }));
}

test("the backend tool list is found, and every name() is a literal this test can read", () => {
  const tools = backendTools();
  // Eight tools today; an empty list would mean the scan broke, not that the check passed.
  assert.ok(tools.length >= 8, `found only ${tools.length} AgentTool classes under ${backendDir}`);
  assert.ok(tools.some((tool) => tool.name === "collect_boss_jobs"), "tools behind DeferrableTool are found too");
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
