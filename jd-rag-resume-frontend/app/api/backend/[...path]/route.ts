const BACKEND_URL = (process.env.BACKEND_API_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

const BFF_CLIENT_IP_HEADER = "x-bff-client-ip";
const CLIENT_IP_SOURCE_HEADERS = [
  "cf-connecting-ip",
  "x-vercel-forwarded-for",
  "x-forwarded-for",
  "x-real-ip",
] as const;

export function clientIpFromHeaders(headers: Headers): string | null {
  for (const name of CLIENT_IP_SOURCE_HEADERS) {
    const value = headers.get(name);
    if (!value) continue;

    const candidate = value.split(",", 1)[0].trim();
    const unwrapped = candidate.startsWith("[") && candidate.endsWith("]")
      ? candidate.slice(1, -1)
      : candidate;
    if (unwrapped.length > 0 && unwrapped.length <= 45 && /^[0-9a-f:.]+$/i.test(unwrapped)) {
      return unwrapped;
    }
  }
  return null;
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

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  if (isCrossSiteWrite(request)) {
    return Response.json(
      { success: false, code: "CROSS_SITE_REQUEST_BLOCKED", message: "跨站请求已被拒绝，请从本站页面发起操作", data: null },
      { status: 403 },
    );
  }
  const { path } = await context.params;
  const incoming = new URL(request.url);
  const target = `${BACKEND_URL}/${path.join("/")}${incoming.search}`;
  const headers = new Headers(request.headers);
  [
    "host",
    "content-length",
    "connection",
    "accept-encoding",
    BFF_CLIENT_IP_HEADER,
    ...CLIENT_IP_SOURCE_HEADERS,
  ].forEach((name) => headers.delete(name));
  const clientIp = clientIpFromHeaders(request.headers);
  if (clientIp) headers.set(BFF_CLIENT_IP_HEADER, clientIp);
  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer();

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
