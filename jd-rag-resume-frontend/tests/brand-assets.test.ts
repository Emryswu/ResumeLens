import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const appDir = join(root, "app");
const brandSource = readFileSync(join(appDir, "components/BrandArt.tsx"), "utf8");

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return tsxFiles(path);
    return path.endsWith(".tsx") ? [path] : [];
  });
}

test("每张品牌素材都存在、是 webp 且不超过 300KB", () => {
  const names = brandSource.match(/type BrandFile = ([^;]+);/)?.[1].match(/"([\w-]+)"/g)?.map((name) => name.slice(1, -1));
  assert.ok(names && names.length > 0, "BrandArt.tsx 里找不到 BrandFile 列表");
  for (const name of names) {
    const file = join(root, "public/brand", `${name}.webp`);
    const bytes = readFileSync(file);
    assert.equal(bytes.subarray(8, 12).toString("ascii"), "WEBP", `${name} 不是 webp`);
    assert.ok(statSync(file).size <= 300 * 1024, `${name} 超过 300KB`);
  }
});

test("图片只经 BrandImage 输出，统一带 alt 与 loading=\"lazy\"", () => {
  assert.match(brandSource, /<img [^>]*alt=\{alt\}[^>]*loading="lazy"/);
  const others = tsxFiles(appDir)
    .map((path) => relative(appDir, path).replaceAll("\\", "/"))
    .filter((file) => file !== "components/BrandArt.tsx" && /<img\b/.test(readFileSync(join(appDir, file), "utf8")));
  assert.deepEqual(others, []);
});
