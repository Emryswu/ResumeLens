import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const appDir = join(dirname(fileURLToPath(import.meta.url)), "../app");
/** Text characters that used to stand in for icons; icons now come from lucide-react. */
const GLYPHS = /[✓✔✗✕×◐◌↗→←⌄＋⚠★☆]/u;

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return tsxFiles(path);
    return path.endsWith(".tsx") ? [path] : [];
  });
}

test("页面不再用 Unicode 字符充当图标", () => {
  const offenders = tsxFiles(appDir)
    .map((path) => relative(appDir, path).replaceAll("\\", "/"))
    .flatMap((file) => readFileSync(join(appDir, file), "utf8").split("\n")
      .map((line, index) => ({ file, line: index + 1, text: line }))
      .filter(({ text }) => GLYPHS.test(text))
      .map(({ file, line, text }) => `${file}:${line} ${text.match(GLYPHS)?.[0]}`));
  assert.deepEqual(offenders, []);
});

test("提示条的 ✓ / ! 不再由 CSS 画出", () => {
  for (const css of ["globals.css", "workspace.css"]) {
    assert.doesNotMatch(readFileSync(join(appDir, css), "utf8"), /content:\s*"[✓!]"/u, css);
  }
});
