import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import * as route from "../app/api/backend/[...path]/route.ts";
import { GET, POST } from "../app/api/backend/[...path]/route.ts";

type Handler = typeof POST;

async function proxyThrough(
  handler: Handler,
  method: string,
  headers: Record<string, string>,
  path: string[] = ["api", "auth", "refresh"],
  { base = "http://localhost:3000", trustedOrigins }: { base?: string; trustedOrigins?: string } = {},
) {
  const originalFetch = globalThis.fetch;
  const originalTrusted = process.env.TRUSTED_ORIGINS;
  const forwarded: Headers[] = [];
  globalThis.fetch = (async (_url: string | URL | Request, init?: RequestInit) => {
    forwarded.push(new Headers(init?.headers));
    return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof fetch;
  if (trustedOrigins === undefined) delete process.env.TRUSTED_ORIGINS;
  else process.env.TRUSTED_ORIGINS = trustedOrigins;
  try {
    const request = new Request(`${base}/api/backend/${path.join("/")}`, {
      method,
      headers,
      body: method === "GET" ? undefined : "{}",
    });
    const response = await handler(request, { params: Promise.resolve({ path }) });
    return { response, forwarded };
  } finally {
    globalThis.fetch = originalFetch;
    if (originalTrusted === undefined) delete process.env.TRUSTED_ORIGINS;
    else process.env.TRUSTED_ORIGINS = originalTrusted;
  }
}

// refresh cookie 由浏览器自动携带。SameSite=Lax 按「站点」判断，兄弟子域发起的 POST
// 仍会带上它，所以写请求必须同源，同站也不行。
for (const site of ["cross-site", "same-site"]) {
  test(`backend proxy blocks a ${site} write before it reaches the backend`, async () => {
    const { response, forwarded } = await proxyThrough(POST, "POST", {
      cookie: "jd-rag-refresh=victim",
      "sec-fetch-site": site,
      origin: "https://sibling.example.com",
    });

    assert.equal(response.status, 403);
    assert.equal((await response.json()).code, "CROSS_SITE_REQUEST_BLOCKED");
    assert.equal(forwarded.length, 0);
  });
}

test("backend proxy falls back to Origin when the browser sends no fetch metadata", async () => {
  const foreign = await proxyThrough(POST, "POST", { origin: "https://evil.example.com" });
  assert.equal(foreign.response.status, 403);
  assert.equal(foreign.forwarded.length, 0);

  const opaque = await proxyThrough(POST, "POST", { origin: "null" });
  assert.equal(opaque.response.status, 403);
  assert.equal(opaque.forwarded.length, 0);

  const own = await proxyThrough(POST, "POST", { origin: "http://localhost:3000" });
  assert.equal(own.response.status, 200);
  assert.equal(own.forwarded.length, 1);
});

test("backend proxy forwards same-origin writes with their fetch metadata", async () => {
  const { response, forwarded } = await proxyThrough(POST, "POST", {
    "sec-fetch-site": "same-origin",
    origin: "http://localhost:3000",
  });

  assert.equal(response.status, 200);
  assert.equal(forwarded.length, 1);
  // 后端还有第二道同样的校验，前提是 BFF 把浏览器给的值原样带过去。
  assert.equal(forwarded[0].get("sec-fetch-site"), "same-origin");
  assert.equal(forwarded[0].get("origin"), "http://localhost:3000");
});

test("backend proxy leaves reads and non-browser clients alone", async () => {
  const crossSiteRead = await proxyThrough(GET, "GET", { "sec-fetch-site": "cross-site" }, ["api", "resumes"]);
  assert.equal(crossSiteRead.response.status, 200);
  assert.equal(crossSiteRead.forwarded.length, 1);

  const script = await proxyThrough(POST, "POST", {}, ["api", "auth", "login"]);
  assert.equal(script.response.status, 200);
  assert.equal(script.forwarded.length, 1);
});

test("backend proxy compares the full origin, so a scheme change is not same-origin", async () => {
  const downgraded = await proxyThrough(POST, "POST", { origin: "http://app.example.com" }, undefined, {
    base: "https://app.example.com",
  });
  assert.equal(downgraded.response.status, 403);
  assert.equal(downgraded.forwarded.length, 0);

  const otherPort = await proxyThrough(POST, "POST", { origin: "http://localhost:8081" });
  assert.equal(otherPort.response.status, 403);
  assert.equal(otherPort.forwarded.length, 0);
});

test("behind a reverse proxy TRUSTED_ORIGINS names the public origin instead of the request URL", async () => {
  // TLS 在反向代理上终止，BFF 看到的是内网 http 地址，浏览器的 Origin 是对外的 https。
  const options = { base: "http://127.0.0.1:3000", trustedOrigins: "https://resume.example.com" };

  const publicPage = await proxyThrough(POST, "POST", { origin: "https://resume.example.com" }, undefined, options);
  assert.equal(publicPage.response.status, 200);
  assert.equal(publicPage.forwarded.length, 1);

  // 配置后只认配置：内网地址本身不再被当成可信来源。
  const internal = await proxyThrough(POST, "POST", { origin: "http://127.0.0.1:3000" }, undefined, options);
  assert.equal(internal.response.status, 403);
  assert.equal(internal.forwarded.length, 0);
});

test("every method the proxy guards is also exported, so the page can send it", () => {
  // 少导出一个方法时，浏览器发出的请求在 BFF 就被 405 挡掉，到不了后端。
  const source = readFileSync(new URL("../app/api/backend/[...path]/route.ts", import.meta.url), "utf8");
  const guarded = [...(source.match(/UNSAFE_METHODS = new Set\(\[([^\]]*)\]\)/)?.[1].matchAll(/"([A-Z]+)"/g) ?? [])].map((match) => match[1]);
  assert.ok(guarded.includes("PATCH"), "the CSRF guard lists PATCH");
  for (const method of ["GET", ...guarded]) {
    assert.equal(typeof (route as Record<string, unknown>)[method], "function", `route.ts must export ${method}`);
  }
});
