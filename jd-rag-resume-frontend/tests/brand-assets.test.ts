import assert from "node:assert/strict";
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

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

/** BrandArt.tsx compiled with the repo's TypeScript and rendered for real, not matched as source text. */
async function loadBrandArt() {
  const { outputText } = ts.transpileModule(brandSource, {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  });
  const dir = join(root, "node_modules/.cache/brand-art-test");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `BrandArt-${process.pid}.mjs`);
  writeFileSync(file, outputText);
  return import(pathToFileURL(file).href);
}

const tsxSources = () => tsxFiles(appDir).map((path) => ({
  file: relative(appDir, path).replaceAll("\\", "/"),
  text: readFileSync(path, "utf8"),
}));

test("图片只经 BrandImage 输出", () => {
  const others = tsxSources().filter(({ file, text }) => file !== "components/BrandArt.tsx" && /<img\b/.test(text)).map(({ file }) => file);
  assert.deepEqual(others, []);
});

test("priority 的图立即加载并提高优先级，其余一律 loading=\"lazy\"，都带 alt", async () => {
  const { BrandImage, SidebarArtCard, BrandCtaCard } = await loadBrandArt();
  const eager = renderToStaticMarkup(createElement(BrandImage, { name: "hero-landscape", priority: true }));
  assert.doesNotMatch(eager, /loading=/);
  assert.match(eager, /fetchpriority="high"/i);
  assert.match(eager, /alt=""/);

  for (const html of [
    renderToStaticMarkup(createElement(BrandImage, { name: "slogan", alt: "标语" })),
    renderToStaticMarkup(createElement(SidebarArtCard)),
    renderToStaticMarkup(createElement(BrandCtaCard)),
  ]) {
    assert.match(html, /loading="lazy"/);
    assert.doesNotMatch(html, /fetchpriority/i);
    assert.match(html, /alt="[^"]*"/);
  }
});

test("只有首屏横幅里的风景图和标语标了 priority", () => {
  const usages = tsxSources().flatMap(({ text }) => [...text.matchAll(/<BrandImage\b([^>]*)\/>/g)].map((match) => match[1]));
  const priority = usages.filter((props) => /\bpriority\b/.test(props)).map((props) => props.match(/name="([\w-]+)"/)?.[1]).sort();
  assert.deepEqual(priority, ["hero-landscape", "slogan"]);
});
