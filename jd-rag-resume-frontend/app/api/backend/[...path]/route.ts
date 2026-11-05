import { isIP } from "node:net";
import ipaddr from "ipaddr.js";

const BACKEND_URL = (process.env.BACKEND_API_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

const BFF_CLIENT_IP_HEADER = "x-bff-client-ip";
const CLIENT_IP_SOURCE_HEADERS = [
  "cf-connecting-ip",
  "x-vercel-forwarded-for",
  "x-forwarded-for",
  "x-real-ip",
] as const;

function address(value: string) {
  return isIP(value) ? ipaddr.process(value).toString() : null;
}

function clientIp(peer: string, headers: Headers): string | null {
  let current = address(peer);
  const trusted = new Set((process.env.TRUSTED_PROXIES ?? "").split(",").filter(s => s.trim()).map(s => {
    const ip = address(s.trim());
    if (!ip) throw new Error("TRUSTED_PROXIES must contain IP addresses");
    return ip;
  }));
  if (!current || !trusted.has(current)) return current;
  const hops = (headers.get("x-forwarded-for") ?? "").split(",");
  for (let i = hops.length - 1; i >= 0 && trusted.has(current); i--) {
    const next = address(hops[i].trim());
    if (!next) break; // An invalid hop cannot vouch for any address to its left.
    current = next;
  }
  return current;
}

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * CSRF 防护：写请求必须来自本站页面。
 *
 * refresh cookie 由浏览器自动携带。SameSite=Lax 按「站点」判断，兄弟子域
 * （same-site 但不同源）发起的 POST 仍会带上它，所以只放行 same-origin。
 * Sec-Fetch-Site 由浏览器填写，页面脚本改不了；none 是用户直接输入地址或书签。
 * 老浏览器没有它时退回 Origin：比较的是完整 origin（协议、主机、端口），
 * http://app 发往 https://app 不算同源。可信来源取 TRUSTED_ORIGINS（逗号分隔，
 * 与后端共用）；未配置时取本次请求自身的 origin。部署在终止 TLS 的反向代理后面时，
 * BFF 看到的是内网地址，必须用 TRUSTED_ORIGINS 写明对外 origin。
 * 两个头都没有说明不是浏览器（脚本、健康检查），不构成 CSRF，放行。
 * GET/HEAD 不改状态，不拦。
 */
export function isCrossSiteWrite(request: Request): boolean {
  if (!UNSAFE_METHODS.has(request.method.toUpperCase())) return false;

  const site = request.headers.get("sec-fetch-site");
  if (site) return site !== "same-origin" && site !== "none";

  const origin = request.headers.get("origin");
  if (!origin) return false;
  const presented = normalizeOrigin(origin);
  return presented === null || !trustedOrigins(request).has(presented);
}

function trustedOrigins(request: Request): Set<string> {
  const configured = (process.env.TRUSTED_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const candidates = configured.length > 0 ? configured : [request.url];
  return new Set(candidates.map(normalizeOrigin).filter((value): value is string => value !== null));
}

/** 按浏览器序列化 Origin 的方式规范化：小写、省略默认端口。"null" 与非法值返回 null。 */
function normalizeOrigin(value: string): string | null {
  if (value === "null") return null;
  try {
    const origin = new URL(value).origin;
    return origin === "null" ? null : origin;
  } catch {
    return null;
  }
}

const JSON_LIMIT = 1024 * 1024;
const UPLOAD_LIMIT = 25 * 1024 * 1024; // Spring multipart.max-request-size

async function boundedBody(request: Request, limit: number) {
  if (Number(request.headers.get("content-length")) > limit) return null;
  if (!request.body) return undefined;
  const reader = request.body.getReader();
  let buffer = new Uint8Array(0);
  let bytes = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return buffer.subarray(0, bytes);
      const size = bytes + value.byteLength;
      if (size > limit) {
        await reader.cancel();
        return null;
      }
      if (size > buffer.length) {
        const grown = new Uint8Array(Math.min(limit, Math.max(size, buffer.length * 2)));
        grown.set(buffer);
        buffer = grown;
      }
      buffer.set(value, bytes);
      bytes = size;
    }
  } finally {
    reader.releaseLock();
  }
}

async function proxy(request: Request, context: { params: Promise<{ path: string[] }>; peer?: string }) {
  if (isCrossSiteWrite(request)) {
    return Response.json(
      { success: false, code: "CROSS_SITE_REQUEST_BLOCKED", message: "跨站请求已被拒绝，请从本站页面发起操作", data: null },
      { status: 403 },
    );
  }
  const { path } = await context.params;
  const api = path.length > 1 && path[0] === "api";
  const health = path.length === 2 && path[0] === "actuator" && path[1] === "health";
  if ((!api && !health) || path.some(segment => !/^[A-Za-z0-9_-]+$/.test(segment))) {
    return new Response(null, { status: 404 });
  }
  // A router-normalized alias must not re-enter the BFF without its TCP identity.
  if (!context.peer) return new Response(null, { status: 404 });
  const incoming = new URL(request.url);
  const target = `${BACKEND_URL}/${path.join("/")}${incoming.search}`;
  const headers = new Headers(request.headers);
  [
    "host",
    "content-length",
    "connection",
    "transfer-encoding",
    "trailer",
    "accept-encoding",
    BFF_CLIENT_IP_HEADER,
    ...CLIENT_IP_SOURCE_HEADERS,
  ].forEach((name) => headers.delete(name));
  const ip = clientIp(context.peer, request.headers);
  if (!ip) return new Response(null, { status: 400 });
  headers.set(BFF_CLIENT_IP_HEADER, ip);
  const limit = path.join("/") === "api/resumes/upload" ? UPLOAD_LIMIT : JSON_LIMIT;
  const body = await boundedBody(request, limit);
  if (body === null) return Response.json({ success: false, code: "PAYLOAD_TOO_LARGE" }, { status: 413 });

  try {
    const upstream = await fetch(target, { method: request.method, headers, body, redirect: "manual" });
    const responseHeaders = new Headers(upstream.headers);
    ["content-length", "content-encoding", "connection"].forEach((name) => responseHeaders.delete(name));
    const setCookie = responseHeaders.get("set-cookie");
    if (setCookie) {
      responseHeaders.set("set-cookie", rewriteUpstreamCookie(setCookie, incoming));
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch {
    return Response.json({ success: false, code: "BACKEND_UNAVAILABLE", message: "后端未启动，请先启动 8080 服务", data: null }, { status: 503 });
  }
}

export function rewriteUpstreamCookie(setCookie: string, incoming: URL) {
  let cookie = setCookie.replace(/Path=\/api\/auth(?=;|$)/i, "Path=/api/backend/api/auth");
  if (incoming.protocol === "https:" && !/;\s*Secure/i.test(cookie)) {
    cookie += "; Secure";
  }
  return cookie;
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
