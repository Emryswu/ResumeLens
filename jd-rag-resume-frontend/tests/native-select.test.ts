import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const appDir = join(dirname(fileURLToPath(import.meta.url)), "..", "app");

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.[jt]sx$/.test(path) ? [path] : [];
  });
}

test("app 内的选择菜单只能使用 ChoiceMenu，禁止原生 select", () => {
  const offenders = sourceFiles(appDir)
    .filter((path) => /<select\b/i.test(readFileSync(path, "utf8")))
    .map((path) => relative(appDir, path).replaceAll("\\", "/"));
  assert.deepEqual(offenders, []);
});
