import type { IncomingMessage, ServerResponse } from "node:http";
import { Readable } from "node:stream";
import { TLSSocket } from "node:tls";
import { resolveHost, sendWebResponse, trustProxy } from "vinext/server/prod-server";
import * as route from "../app/api/backend/[...path]/route.ts";

const handlers = new Map(Object.entries({
  GET: route.GET, POST: route.POST, PUT: route.PUT, PATCH: route.PATCH, DELETE: route.DELETE,
  HEAD: route.GET,
}));

function webRequest(req: IncomingMessage) {
  const headers = new Headers();
  for (const [name, value] of Object.entries(req.headers)) {
    if (Array.isArray(value)) value.forEach(item => headers.append(name, item));
    else if (value !== undefined) headers.set(name, value);
  }
  const https = req.socket instanceof TLSSocket || (trustProxy && headers.get("x-forwarded-proto")?.split(",")[0].trim() === "https");
  const url = new URL(req.url!, `${https ? "https" : "http"}://${resolveHost(req, "localhost")}`);
  const init: RequestInit & { duplex?: "half" } = { method: req.method, headers };
  if (req.method !== "GET" && req.method !== "HEAD") {
    // Cancelling an oversized upload must leave the socket writable for the 413.
    const body = Readable.from(req.iterator({ destroyOnReturn: false }), { objectMode: false });
    init.body = Readable.toWeb(body) as ReadableStream<Uint8Array<ArrayBuffer>>;
    init.duplex = "half";
  }
  return new Request(url, init);
}

// Intercept before vinext normalizes the raw path or discards the socket.
export async function serveBff(req: IncomingMessage, res: ServerResponse, next: () => void) {
  const prefix = "/api/backend/";
  const pathname = req.url?.split("?", 1)[0] ?? "";
  if (!pathname.startsWith(prefix)) return next();
  const handler = handlers.get(req.method ?? "GET");
  if (!handler) {
    res.writeHead(req.method === "OPTIONS" ? 204 : 405, { Allow: [...handlers.keys(), "OPTIONS"].join(", ") });
    res.end();
    return;
  }
  const path = pathname.slice(prefix.length).split("/");
  const response = await handler(webRequest(req), {
    params: Promise.resolve({ path }),
    peer: req.socket.remoteAddress,
  });
  // Navigation can close an SSE connection while the backend is opening the response.
  if (res.destroyed) {
    await response.body?.cancel();
    return;
  }
  if (response.status === 413) res.shouldKeepAlive = false;
  await sendWebResponse(response, req, res, false);
}
