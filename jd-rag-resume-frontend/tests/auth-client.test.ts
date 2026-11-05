import assert from "node:assert/strict";
import test from "node:test";
import {
  ApiError,
  AUTH_EXPIRED_EVENT,
  AUTH_SESSION_CHANGED_CODE,
  apiRequest,
  clearAuthSession,
  getAuthSessionId,
  isAuthSessionChangedError,
  isAuthSessionCurrent,
  logoutSession,
  refreshSession,
  setAccessToken,
  visibleApiErrorMessage,
} from "../app/lib/api.ts";
import {
  POST as proxyPost,
  rewriteUpstreamCookie,
} from "../app/api/backend/[...path]/route.ts";

const session = {
  tokenType: "Bearer" as const,
  accessToken: "fresh-access-token",
  expiresInSeconds: 900,
  user: { id: 1, username: "arthur", displayName: "Arthur", email: "arthur@example.com" },
};

const unauthorized = () => Response.json(
  { success: false, code: "UNAUTHORIZED", message: "expired", data: null },
  { status: 401 },
);

test.afterEach(() => {
  clearAuthSession();
});

test("a 401 refreshes once and retries with the new in-memory token", async () => {
  setAccessToken("expired-access-token");
  const calls: Array<{ url: string; authorization: string | null }> = [];
  const responses = [
    Response.json({ success: false, code: "UNAUTHORIZED", message: "expired", data: null }, { status: 401 }),
    Response.json({ success: true, code: "OK", message: "success", data: session }),
    Response.json({ success: true, code: "OK", message: "success", data: { value: 42 } }),
  ];

  globalThis.fetch = async (input, init) => {
    calls.push({
      url: String(input),
      authorization: new Headers(init?.headers).get("Authorization"),
    });
    return responses.shift()!;
  };

  const result = await apiRequest<{ value: number }>("/api/protected");

  assert.deepEqual(result, { value: 42 });
  assert.equal(calls.length, 3);
  assert.equal(calls[0].authorization, "Bearer expired-access-token");
  assert.equal(calls[1].url, "/api/backend/api/auth/refresh");
  assert.equal(calls[2].authorization, "Bearer fresh-access-token");
});

test("parallel refresh calls share one browser request", async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    await Promise.resolve();
    return Response.json({ success: true, code: "OK", message: "success", data: session });
  };

  const [first, second] = await Promise.all([refreshSession(), refreshSession()]);

  assert.equal(calls, 1);
  assert.equal(first?.accessToken, "fresh-access-token");
  assert.equal(second?.accessToken, "fresh-access-token");
});

test("login waits for bootstrap refresh so its Set-Cookie response wins", async () => {
  let releaseRefresh!: () => void;
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });
  const loginSession = {
    ...session,
    accessToken: "new-login-token",
    user: { ...session.user, id: 2, username: "new-user" },
  };
  let protectedAuthorization = "";
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.endsWith("/api/auth/refresh")) {
      await refreshGate;
      return Response.json({ success: true, code: "OK", message: "success", data: session });
    }
    if (url.endsWith("/api/auth/login")) {
      return Response.json({ success: true, code: "OK", message: "success", data: loginSession });
    }
    protectedAuthorization = new Headers(init?.headers).get("Authorization") || "";
    return Response.json({ success: true, code: "OK", message: "success", data: { ok: true } });
  };

  const bootstrap = refreshSession();
  const loginPromise = apiRequest<AuthResponse>(
    "/api/auth/login",
    { method: "POST", body: JSON.stringify({ username: "new-user", password: "secret" }) },
    { auth: false },
  );
  await Promise.resolve();
  releaseRefresh();
  assert.equal((await bootstrap)?.accessToken, "fresh-access-token");
  const login = await loginPromise;
  setAccessToken(login.accessToken);

  await apiRequest<{ ok: boolean }>("/api/protected");
  assert.equal(protectedAuthorization, "Bearer new-login-token");
});

test("a stale refresh cannot overwrite a newer explicitly installed token", async () => {
  let releaseRefresh!: () => void;
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });
  let protectedAuthorization = "";
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith("/api/auth/refresh")) {
      await refreshGate;
      return Response.json({ success: true, code: "OK", message: "success", data: session });
    }
    protectedAuthorization = new Headers(init?.headers).get("Authorization") || "";
    return Response.json({ success: true, code: "OK", message: "success", data: { ok: true } });
  };

  const staleRefresh = refreshSession();
  setAccessToken("explicit-new-token");
  releaseRefresh();

  assert.equal(await staleRefresh, null);
  await apiRequest<{ ok: boolean }>("/api/protected");
  assert.equal(protectedAuthorization, "Bearer explicit-new-token");
});

test("a new login waits for an in-flight logout response", async () => {
  let releaseLogout!: () => void;
  const logoutGate = new Promise<void>((resolve) => {
    releaseLogout = resolve;
  });
  const calls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    calls.push(url);
    if (url.endsWith("/api/auth/logout")) {
      await logoutGate;
      return Response.json({ success: true, code: "OK", message: "success", data: null });
    }
    return Response.json({ success: true, code: "OK", message: "success", data: session });
  };

  const logout = logoutSession();
  const login = apiRequest<AuthResponse>(
    "/api/auth/login",
    { method: "POST", body: JSON.stringify({ username: "arthur", password: "secret" }) },
    { auth: false },
  );
  await Promise.resolve();

  assert.deepEqual(calls, ["/api/backend/api/auth/logout"]);
  releaseLogout();
  await logout;
  assert.equal((await login).accessToken, "fresh-access-token");
  assert.deepEqual(calls, [
    "/api/backend/api/auth/logout",
    "/api/backend/api/auth/login",
  ]);
});

test("a successful 204 response resolves without trying to parse an envelope", async () => {
  globalThis.fetch = async () => new Response(null, { status: 204 });

  const result = await apiRequest<void>("/api/resumes/1", { method: "DELETE" });

  assert.equal(result, undefined);
});

test("API failures preserve the backend code and HTTP status", async () => {
  globalThis.fetch = async () => Response.json(
    { success: false, code: "SEMANTIC_EMBEDDING_STALE", message: "refresh required", data: null },
    { status: 409 },
  );

  await assert.rejects(
    () => apiRequest("/api/job-descriptions/matches?resumeId=7"),
    (reason) => reason instanceof ApiError
      && reason.code === "SEMANTIC_EMBEDDING_STALE"
      && reason.status === 409,
  );
});

test("the backend proxy rewrites the refresh cookie path for the browser route", async () => {
  globalThis.fetch = async () => new Response("{}", {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "Set-Cookie": "jd-rag-refresh=opaque; Path=/api/auth; HttpOnly; SameSite=Lax",
    },
  });
  const request = new Request("http://localhost/api/backend/api/auth/login", {
    method: "POST",
    body: "{}",
    headers: { "Content-Type": "application/json" },
  });

  const response = await proxyPost(request, {
    params: Promise.resolve({ path: ["api", "auth", "login"] }), peer: "127.0.0.1",
  });

  assert.match(
    response.headers.get("set-cookie") || "",
    /Path=\/api\/backend\/api\/auth(?:;|$)/,
  );
});

test("only follows XFF hops vouched for by the TCP peer", async (t) => {
  const before = process.env.TRUSTED_PROXIES;
  process.env.TRUSTED_PROXIES = "192.0.2.1,192.0.2.2";
  t.after(() => { if (before === undefined) delete process.env.TRUSTED_PROXIES; else process.env.TRUSTED_PROXIES = before; });
  const seen: Headers[] = [];
  globalThis.fetch = async (_input, init) => {
    seen.push(new Headers(init?.headers));
    return new Response("{}");
  };
  for (const peer of ["203.0.113.1", "192.0.2.1"]) {
    for (const forged of ["198.51.100.1", "198.51.100.2"]) {
      const request = new Request("http://localhost/api/backend/api/auth/login", {
        method: "POST", headers: {
          "x-forwarded-for": [forged, "203.0.113.9", "192.0.2.2"].join(", "),
          "cf-connecting-ip": forged, "x-real-ip": forged, "x-bff-client-ip": forged,
        },
      });
      await proxyPost(request, { params: Promise.resolve({ path: ["api", "auth", "login"] }), peer });
    }
  }
  assert.deepEqual(seen.map(h => h.get("x-bff-client-ip")), ["203.0.113.1", "203.0.113.1", "203.0.113.9", "203.0.113.9"]);
  for (const h of seen) {
    for (const name of ["x-forwarded-for", "x-real-ip", "cf-connecting-ip"]) assert.equal(h.has(name), false);
  }
});

test("the backend proxy adds Secure on HTTPS but leaves local HTTP cookies intact", () => {
  const cookie = "jd-rag-refresh=opaque; Path=/api/auth; HttpOnly; SameSite=Lax";
  const httpCookie = rewriteUpstreamCookie(cookie, new URL("http://127.0.0.1:3000/api/backend/api/auth/login"));
  const httpsCookie = rewriteUpstreamCookie(cookie, new URL("https://example.com/api/backend/api/auth/login"));
  assert.equal(httpCookie.includes("Secure"), false);
  assert.match(httpsCookie, /;\s*Secure/);
});

/*
 * Session isolation across account switches.
 *
 * These pin down side effects, not just which error surfaces: the number of
 * fetch calls, which Authorization each one carried, whether /api/auth/refresh
 * was reached at all, and whether AUTH_EXPIRED_EVENT actually fired. Asserting
 * only on the thrown code would still pass if the guarded branch were never
 * entered, which is exactly how two regressions slipped through review here.
 */

type MutableGlobal = Record<string, unknown>;

const savedGlobals: { window?: unknown; localStorage?: unknown } = {};

/**
 * api.ts only dispatches AUTH_EXPIRED_EVENT when `window` exists, and its
 * legacy-storage cleanup then reaches for `localStorage`, so the two have to be
 * installed and removed together.
 */
function installBrowserGlobals(): EventTarget {
  const mutable = globalThis as unknown as MutableGlobal;
  savedGlobals.window = mutable.window;
  savedGlobals.localStorage = mutable.localStorage;
  const target = new EventTarget();
  mutable.window = target;
  mutable.localStorage = { removeItem() {} };
  return target;
}

function restoreBrowserGlobals() {
  const mutable = globalThis as unknown as MutableGlobal;
  if (savedGlobals.window === undefined) delete mutable.window;
  else mutable.window = savedGlobals.window;
  if (savedGlobals.localStorage === undefined) delete mutable.localStorage;
  else mutable.localStorage = savedGlobals.localStorage;
  savedGlobals.window = undefined;
  savedGlobals.localStorage = undefined;
}

// Registered after the file's existing afterEach, so clearAuthSession() still
// runs while the stubs are in place.
test.afterEach(restoreBrowserGlobals);

test("a 401 from an abandoned session never replays with the next account's token", async () => {
  installBrowserGlobals();
  setAccessToken("account-A-token");
  const calls: Array<{ url: string; authorization: string | null; body: unknown }> = [];
  let releaseA!: (response: Response) => void;

  globalThis.fetch = async (input, init) => {
    calls.push({
      url: String(input),
      authorization: new Headers(init?.headers).get("Authorization"),
      body: init?.body,
    });
    if (calls.length === 1) {
      return new Promise<Response>((resolve) => {
        releaseA = resolve;
      });
    }
    return Response.json({ success: true, code: "OK", message: "success", data: { ok: true } });
  };

  const staleWrite = apiRequest("/api/resumes", {
    method: "POST",
    body: JSON.stringify({ rawText: "PRIVATE_RESUME_FROM_A" }),
  });

  clearAuthSession();
  setAccessToken("account-B-token");
  releaseA(unauthorized());

  await assert.rejects(
    () => staleWrite,
    (reason) => reason instanceof ApiError && reason.code === AUTH_SESSION_CHANGED_CODE,
  );

  // No replay at all: A's body must never leave again, least of all as B.
  assert.equal(calls.length, 1);
  assert.equal(calls[0].authorization, "Bearer account-A-token");

  // B is untouched and still usable.
  await apiRequest<{ ok: boolean }>("/api/protected");
  assert.equal(calls.length, 2);
  assert.equal(calls[1].authorization, "Bearer account-B-token");
});

test("a failed refresh reports an expired session rather than an account switch", async () => {
  const browserWindow = installBrowserGlobals();
  const expiredEvents: string[] = [];
  browserWindow.addEventListener(AUTH_EXPIRED_EVENT, () => expiredEvents.push(AUTH_EXPIRED_EVENT));
  setAccessToken("expired-access-token");
  const calls: string[] = [];

  globalThis.fetch = async (input) => {
    calls.push(String(input));
    return unauthorized();
  };

  await assert.rejects(
    () => apiRequest("/api/protected"),
    (reason) => reason instanceof ApiError
      && reason.code === "UNAUTHORIZED"
      && reason.code !== AUTH_SESSION_CHANGED_CODE,
  );

  assert.deepEqual(calls, [
    "/api/backend/api/protected",
    "/api/backend/api/auth/refresh",
  ]);
  // The page clears its workspace on this event; losing it leaves a half
  // signed-out UI holding the previous user's data.
  assert.deepEqual(expiredEvents, [AUTH_EXPIRED_EVENT]);
});

test("a rejected login never triggers a refresh or moves the session", async () => {
  installBrowserGlobals();
  const sessionIdBefore = getAuthSessionId();
  const calls: string[] = [];

  globalThis.fetch = async (input) => {
    calls.push(String(input));
    return Response.json(
      { success: false, code: "BAD_CREDENTIALS", message: "用户名或密码错误", data: null },
      { status: 401 },
    );
  };

  await assert.rejects(
    () => apiRequest(
      "/api/auth/login",
      { method: "POST", body: JSON.stringify({ username: "arthur", password: "wrong" }) },
      { auth: false },
    ),
    (reason) => reason instanceof ApiError
      && reason.code === "BAD_CREDENTIALS"
      && reason.status === 401,
  );

  assert.deepEqual(calls, ["/api/backend/api/auth/login"]);
  assert.equal(getAuthSessionId(), sessionIdBefore);
});

test("concurrent 401s sharing one failed refresh both report expiry", async () => {
  const browserWindow = installBrowserGlobals();
  let expiredEvents = 0;
  browserWindow.addEventListener(AUTH_EXPIRED_EVENT, () => {
    expiredEvents += 1;
  });
  setAccessToken("expired-access-token");
  let refreshCalls = 0;
  let releaseRefresh!: () => void;
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });

  globalThis.fetch = async (input) => {
    if (String(input).endsWith("/api/auth/refresh")) {
      refreshCalls += 1;
      await refreshGate;
      return unauthorized();
    }
    return unauthorized();
  };

  const first = apiRequest("/api/resumes");
  const second = apiRequest("/api/job-descriptions");
  // Drain the microtask queue so both requests are parked on the shared
  // refresh before it settles.
  await new Promise((resolve) => setImmediate(resolve));
  releaseRefresh();

  const outcomes = await Promise.allSettled([first, second]);
  for (const outcome of outcomes) {
    assert.equal(outcome.status, "rejected");
    const reason = (outcome as PromiseRejectedResult).reason;
    assert.ok(reason instanceof ApiError);
    // The second request must not be misread as an account switch just
    // because the first one already cleaned the session up.
    assert.equal(reason.code, "UNAUTHORIZED");
  }
  assert.equal(refreshCalls, 1);
  assert.equal(expiredEvents, 2);
});

test("AUTH_SESSION_CHANGED is a silent cancel, not toast copy", () => {
  const cancelled = new ApiError(AUTH_SESSION_CHANGED_CODE, "登录状态已变更，原请求已作废", 401);
  assert.equal(isAuthSessionChangedError(cancelled), true);
  assert.equal(visibleApiErrorMessage(cancelled, "无法连接后端"), null);

  const unauthorizedError = new ApiError("UNAUTHORIZED", "请先登录", 401);
  assert.equal(isAuthSessionChangedError(unauthorizedError), false);
  assert.equal(visibleApiErrorMessage(unauthorizedError, "无法连接后端"), "请先登录");
  assert.equal(visibleApiErrorMessage(new Error("timeout"), "无法连接后端"), "timeout");
  assert.equal(visibleApiErrorMessage("boom", "无法连接后端"), "无法连接后端");
});

test("workspace load must not commit after the captured login session changes", () => {
  setAccessToken("account-A-token");
  const captured = getAuthSessionId();
  assert.equal(isAuthSessionCurrent(captured), true);

  clearAuthSession();
  setAccessToken("account-B-token");

  assert.equal(isAuthSessionCurrent(captured), false);
  assert.notEqual(getAuthSessionId(), captured);
  // The current session is healthy; only the abandoned load is discarded.
  assert.equal(isAuthSessionCurrent(getAuthSessionId()), true);
});
