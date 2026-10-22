import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testsDir = dirname(fileURLToPath(import.meta.url));

/** Comments may legitimately mention :root; only real rules are checked. */
function withoutComments(css: string): string {
  return css.replace(/\r\n/g, "\n").replace(/\/\*[\s\S]*?\*\//g, "");
}

const workspaceCss = withoutComments(readFileSync(join(testsDir, "../app/workspace.css"), "utf8"));
const globalsCss = withoutComments(readFileSync(join(testsDir, "../app/globals.css"), "utf8"));

/**
 * workspace.css is imported from the root layout, so anything it puts on :root
 * also repaints the sign-in page, which is not wrapped in .refined-workspace.
 */
test("工作台样式不在 :root 上重定义全站配色变量", () => {
  const rootRules = [...workspaceCss.matchAll(/:root[^{]*\{[^}]*\}/g)].map((match) => match[0]);
  assert.deepEqual(
    rootRules,
    [],
    "workspace.css 里的变量要写在 .refined-workspace 上，否则会漏到登录页",
  );
});

test("被工作台覆盖的变量在 .refined-workspace 作用域内声明", () => {
  const overridden = ["--ink", "--muted", "--paper", "--panel", "--line", "--mint-dark"];
  const scoped = workspaceCss.match(/\.refined-workspace \{([^}]*--[^}]*)\}/);
  assert.ok(scoped, "找不到 .refined-workspace 上的变量声明");

  for (const name of overridden) {
    assert.match(globalsCss, new RegExp(`${name}:`), `${name} 应当由 globals.css 提供全站默认值`);
    assert.match(scoped[1], new RegExp(`${name}:`), `${name} 的工作台取值应当收在 .refined-workspace 内`);
  }
});

test("工作台自绘底色，不依赖 body 上的全局 --paper", () => {
  assert.match(globalsCss, /body \{[^}]*background: var\(--paper\)/);
  assert.match(workspaceCss, /\.refined-workspace \{[^}]*background: var\(--paper\)/);
});

type Rule = { selector: string; body: string };

/** Innermost rules only; @media wrappers are dropped and @font-face/@keyframes skipped. */
function rules(css: string): Rule[] {
  return [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map((match) => ({ selector: match[1].replace(/^[\s\S]*@media[^{]*$/, "").trim(), body: match[2] }))
    .filter((rule) => rule.selector && !rule.selector.startsWith("@") && !/^(from|to|\d+%)$/.test(rule.selector));
}

/** Rendered only on the sign-in page, which keeps its own type and palette. */
const LOGIN_ONLY = /^(\.auth-|\.mobile-brand|\.status-pill|\.text-button|\.model-line|body$|html$|:root$|\*)/;
/** Shared by the sign-in page and the shell; the shell overrides them in workspace.css. */
const SHARED_WITH_LOGIN = ["label", ".eyebrow", ".message"];

const shellRules = [...rules(globalsCss), ...rules(workspaceCss)].filter((rule) => {
  const selectors = rule.selector.split(",").map((part) => part.trim());
  return !selectors.every((part) => LOGIN_ONLY.test(part) || SHARED_WITH_LOGIN.includes(part));
});

function tokens(css: string, scope: RegExp): Map<string, string> {
  const block = css.match(scope)?.[1] ?? "";
  return new Map([...block.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((match) => [match[1], match[2].trim()]));
}
const rootTokens = tokens(globalsCss, /:root \{([^}]*)\}/);
const shellTokens = new Map([...rootTokens, ...tokens(workspaceCss, /\.refined-workspace \{([^}]*--[^}]*)\}/)]);
const resolve = (value: string) => value.replace(/var\((--[\w-]+)\)/g, (_, name: string) => shellTokens.get(name) ?? name);

function pixels(value: string): number {
  const resolved = resolve(value.trim());
  if (resolved === "0") return 0;
  const match = resolved.match(/^(\d+(?:\.\d+)?)px$/);
  assert.ok(match, `无法解析字号 ${value}`);
  return Number(match[1]);
}

test("外壳内正文不小于 14px，次要文字不小于 13px", () => {
  assert.equal(rootTokens.get("--text-meta"), "13px");
  assert.equal(rootTokens.get("--text-body"), "14px");
  const tooSmall = shellRules.flatMap((rule) => [
    ...[...rule.body.matchAll(/font-size:\s*([^;]+)/g)].map((match) => match[1]),
    ...[...rule.body.matchAll(/font:\s*\d+\s+(\d+px)/g)].map((match) => match[1]),
  ].filter((value) => { const size = pixels(value); return size !== 0 && size < 13; })
    .map((value) => `${rule.selector}: ${value}`));
  assert.deepEqual(tooSmall, []);
});

test("与登录页共用的选择器在外壳内被覆盖到字号基线以上", () => {
  for (const selector of SHARED_WITH_LOGIN) {
    const override = rules(workspaceCss).find((rule) => rule.selector === `.refined-workspace ${selector}`);
    assert.ok(override, `workspace.css 缺少 .refined-workspace ${selector}`);
    const size = override.body.match(/font-size:\s*([^;]+)/)?.[1];
    assert.ok(size && pixels(size) >= 13, `.refined-workspace ${selector} 字号应不小于 13px`);
  }
});

test("操作结果提示吸在顶栏下方，滚到页面下部操作时也看得见", () => {
  const toast = rules(workspaceCss).find((rule) => rule.selector === ".refined-workspace .toast" && /position:/.test(rule.body));
  assert.ok(toast, "workspace.css 缺少 .refined-workspace .toast 的定位规则");
  assert.match(toast.body, /position:\s*sticky/, "提示框不能是 static，否则在页面下部上传失败时用户看不到报错");
  const topbar = rules(workspaceCss).find((rule) => rule.selector === ".app-topbar" && /position:\s*sticky/.test(rule.body));
  assert.ok(topbar, "找不到吸顶的 .app-topbar");
  const top = toast.body.match(/(?:^|[;\s])top:\s*([^;]+)/)?.[1];
  const barHeight = topbar.body.match(/min-height:\s*([^;]+)/)?.[1];
  assert.ok(top && barHeight, "提示框需要 top，顶栏需要 min-height");
  assert.ok(pixels(top) > pixels(barHeight), "提示框的 top 要让出吸顶顶栏的高度，否则会被顶栏盖住");
});

function luminance(hex: string): number {
  const full = hex.length === 4 ? `#${[...hex.slice(1)].map((c) => c + c).join("")}` : hex;
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(full.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

test("外壳文字颜色在其底色上达到 WCAG AA（4.5:1）", () => {
  const paper = resolve("var(--paper)");
  const panel = resolve("var(--panel)");
  for (const name of ["--ink", "--muted", "--mint-dark"]) {
    for (const ground of [paper, panel]) {
      assert.ok(contrast(resolve(`var(${name})`), ground) >= 4.5, `${name} 在 ${ground} 上对比度不足`);
    }
  }
  const failures: string[] = [];
  for (const rule of shellRules) {
    const color = rule.body.match(/(?:^|[;\s])color:\s*([^;]+)/)?.[1];
    if (!color) continue;
    const text = resolve(color.trim());
    if (!/^#[0-9a-f]{3,6}$/i.test(text)) continue;
    const background = rule.body.match(/background(?:-color)?:\s*([^;]+)/)?.[1];
    const ground = background ? resolve(background.trim()) : null;
    const grounds = ground && /^#[0-9a-f]{3,6}$/i.test(ground) ? [ground] : [paper, panel];
    for (const each of grounds) {
      const ratio = contrast(text, each);
      if (ratio < 4.5) failures.push(`${rule.selector}: ${text} on ${each} = ${ratio.toFixed(2)}`);
    }
  }
  assert.deepEqual(failures, []);
});

test("外壳内不再使用宋体，宋体只留给登录页", () => {
  const serif = shellRules.filter((rule) => /ResumeSerif/.test(rule.body)).map((rule) => rule.selector);
  // .brand-mark is shared with the sign-in page; the sidebar overrides its font.
  assert.deepEqual(serif, [".brand-mark"]);
  assert.match(workspaceCss, /\.app-sidebar \.brand-mark \{[^}]*font: 600 \d+px system-ui/);
});
