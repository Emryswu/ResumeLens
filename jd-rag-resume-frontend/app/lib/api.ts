/** Shared API client and domain types for ResumeLens. */

export type User = { id: number; username: string; displayName: string; email: string };

export type Resume = {
  id: number;
  title: string;
  candidateName: string;
  phone?: string;
  email?: string;
  rawText?: string;
  originalFileName?: string;
  contentType?: string;
  fileExtension?: string;
  fileSize?: number;
  createdAt?: string;
  updatedAt?: string;
};

export type Job = {
  id: number;
  title: string;
  companyName: string;
  location?: string;
  employmentType?: string;
  description: string;
  requirements?: string;
  sourcePlatform?: string;
  sourceUrl?: string;
  sourceJobId?: string;
  contentFingerprint?: string;
  lastSeenAt?: string;
  createdAt?: string;
  updatedAt?: string;
};

/** Cheap whole-document cosine score; this is not the final RAG/LLM match score. */
export type JobSemanticMatch = {
  job: Job;
  similarity: number;
};

export type JobDraft = {
  title: string;
  companyName: string;
  location?: string;
  employmentType?: string;
  description: string;
  requirements?: string;
};

export type Analysis = {
  id: number;
  resumeId: number;
  resumeTitle: string;
  jobDescriptionId: number;
  jobTitle: string;
  matchScore: number | null;
  status: "PENDING" | "COMPLETED" | "FAILED";
  summary?: string;
  retrievedContext?: string;
  strengths?: string;
  missingSkills?: string;
  improvementSuggestions?: string;
  interviewQuestions?: string;
  createdAt: string;
};

export type AnalysisSummary = Pick<
  Analysis,
  "id" | "resumeId" | "jobDescriptionId" | "matchScore" | "status" | "createdAt"
>;

export type AiStatus = {
  mockEnabled: boolean;
  model: string;
  /** 服务端当前生效的检索阈值与 Top-K，避免界面写死后与配置脱节 */
  minSimilarity: number;
  topK: number;
  pendingTimeoutMinutes?: number;
};

export type ApiEnvelope<T> = { success: boolean; code: string; message: string; data: T };
export type PageData<T> = { content: T[]; totalElements: number };
export type AuthResponse = {
  tokenType: "Bearer";
  accessToken: string;
  expiresInSeconds: number;
  user: User;
};

export const API_PREFIX = "/api/backend";
export const AUTH_EXPIRED_EVENT = "jd-rag-auth-expired";
/** Raised when a request outlived the login session it was issued under. */
export const AUTH_SESSION_CHANGED_CODE = "AUTH_SESSION_CHANGED";

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

/** True when a request was cancelled because its login session is no longer current. */
export function isAuthSessionChangedError(reason: unknown): reason is ApiError {
  return reason instanceof ApiError && reason.code === AUTH_SESSION_CHANGED_CODE;
}

/**
 * Toast copy for a failed request. AUTH_SESSION_CHANGED is an expected cancel
 * after a logout/login, so callers must not surface it to the user who is
 * current now.
 */
export function visibleApiErrorMessage(reason: unknown, fallback: string): string | null {
  if (isAuthSessionChangedError(reason)) return null;
  return reason instanceof Error ? reason.message : fallback;
}

type ApiRequestOptions = {
  auth?: boolean;
  retryAuth?: boolean;
};

type SetAccessTokenOptions = {
  /**
   * True only for token rotation inside an already established login session
   * (refresh). Logging in is a new session and must leave this false.
   */
  continuesSession?: boolean;
};

let accessToken = "";
let refreshPromise: Promise<AuthResponse | null> | null = null;
let refreshGeneration = -1;
let logoutPromise: Promise<void> | null = null;
let authGeneration = 0;
/**
 * Identifies the current login session. Unlike authGeneration it does NOT
 * advance on refresh rotation, so callers can distinguish "same user, newer
 * token" from "different user is now logged in".
 */
let authSessionId = 0;
/**
 * Counts the session clears caused by our own refresh failure. A failed
 * refresh ends the session legitimately, so apiRequest must not mistake the
 * resulting authSessionId bump for "another account logged in".
 */
let refreshFailureClears = 0;

/** Snapshot of the current login session; compare after awaiting to detect switches. */
export function getAuthSessionId(): number {
  return authSessionId;
}

/** True when the login session is still the one the caller captured earlier. */
export function isAuthSessionCurrent(sessionId: number): boolean {
  return sessionId === authSessionId;
}

export function setAccessToken(nextToken: string, options: SetAccessTokenOptions = {}) {
  authGeneration += 1;
  if (!options.continuesSession) authSessionId += 1;
  accessToken = nextToken;
  removeLegacyAuthStorage();
}

export function clearAuthSession() {
  authGeneration += 1;
  authSessionId += 1;
  accessToken = "";
  removeLegacyAuthStorage();
}

export async function refreshSession(): Promise<AuthResponse | null> {
  if (logoutPromise) await logoutPromise;
  const generation = authGeneration;
  if (refreshPromise) {
    if (refreshGeneration === generation) return refreshPromise;
    await refreshPromise;
    return refreshSession();
  }

  refreshGeneration = generation;
  refreshPromise = requestRefreshWithBrowserLock()
    .then((session) => {
      if (generation !== authGeneration) return null;
      // Rotation within the same login session: keep authSessionId stable so
      // in-flight requests issued before the refresh remain retryable.
      setAccessToken(session.accessToken, { continuesSession: true });
      return session;
    })
    .catch(() => {
      if (generation === authGeneration) {
        refreshFailureClears += 1;
        clearAuthSession();
      }
      return null;
    })
    .finally(() => {
      refreshPromise = null;
      refreshGeneration = -1;
    });
  return refreshPromise;
}

export function logoutSession(): Promise<void> {
  if (logoutPromise) return logoutPromise;

  clearAuthSession();
  logoutPromise = requestLogoutWithBrowserLock()
    .catch(() => {
      // Local session cleanup must still complete when the backend is unavailable.
    })
    .finally(() => {
      clearAuthSession();
      logoutPromise = null;
    });
  return logoutPromise;
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  options: ApiRequestOptions = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  const response = await authorizedFetch(path, { ...init, headers }, options);
  if (response.status === 204) {
    return undefined as T;
  }
  const payload = (await response.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!response.ok || !payload?.success) {
    throw new ApiError(
      payload?.code || `HTTP_${response.status}`,
      payload?.message || `请求失败（${response.status}）`,
      response.status,
    );
  }
  return payload.data;
}

/**
 * POSTs JSON and returns the response body as a stream (Server-Sent Events).
 * Shares token handling and 401 recovery with apiRequest. Rejections happen
 * before the stream opens and arrive as the usual JSON envelope, so the status
 * is checked before anything is read as SSE.
 */
export async function apiStream(
  path: string,
  body: unknown,
  signal?: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  const headers = new Headers({ Accept: "text/event-stream, application/json" });
  const response = await authorizedFetch(path, { method: "POST", headers, body: JSON.stringify(body), signal });
  if (!response.ok || !response.body) {
    const payload = (await response.json().catch(() => null)) as ApiEnvelope<unknown> | null;
    throw new ApiError(
      payload?.code || `HTTP_${response.status}`,
      payload?.message || `请求失败（${response.status}）`,
      response.status,
    );
  }
  return response.body;
}

/**
 * Sends with the current access token and recovers from a 401 once. Returns the
 * final Response unread; callers decide how to parse it.
 */
async function authorizedFetch(
  path: string,
  init: RequestInit,
  options: ApiRequestOptions = {},
): Promise<Response> {
  if (logoutPromise) await logoutPromise;
  const startsExplicitSession = options.auth === false && /^\/api\/auth\/(?:login|register)$/.test(path);
  if (startsExplicitSession && refreshPromise) {
    await refreshPromise;
  }
  const headers = new Headers(init.headers);
  const requestAccessToken = accessToken;
  // Captured together with the token: a 401 is only ever retried while this
  // still matches, so a stale request can never borrow another account's token.
  const requestAuthSessionId = authSessionId;
  if (options.auth !== false && requestAccessToken) {
    headers.set("Authorization", `Bearer ${requestAccessToken}`);
  }
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json; charset=utf-8");
  }
  const send = () => fetch(`${API_PREFIX}${path}`, {
      ...init,
      headers,
      credentials: "same-origin",
    });
  const response = startsExplicitSession ? await requestWithBrowserAuthLock(send) : await send();
  if (response.status === 401 && options.auth !== false) {
    if (requestAuthSessionId !== authSessionId) {
      // The session that issued this request is gone. Drop it without retrying
      // and without notifyAuthExpired(), which would sign out the new session.
      throw new ApiError(
        AUTH_SESSION_CHANGED_CODE,
        "登录状态已变更，原请求已作废",
        response.status,
      );
    }
    // The session this request still counts as its own. A failed refresh ends
    // that session legitimately and bumps authSessionId, so the id we accept
    // has to absorb that bump; every later check uses this value, never the
    // raw snapshot, or the compensation gets applied in one place and skipped
    // in another.
    let ownedSessionId = requestAuthSessionId;
    if (options.retryAuth !== false) {
      if (requestAccessToken && requestAccessToken !== accessToken) {
        return authorizedFetch(path, init, { ...options, retryAuth: false });
      }
      const clearsBeforeRefresh = refreshFailureClears;
      const session = await refreshSession();
      const selfInflictedBumps = refreshFailureClears - clearsBeforeRefresh;
      const sessionIsOurs = authSessionId === requestAuthSessionId + selfInflictedBumps;
      if (!sessionIsOurs) {
        throw new ApiError(
          AUTH_SESSION_CHANGED_CODE,
          "登录状态已变更，原请求已作废",
          response.status,
        );
      }
      ownedSessionId = authSessionId;
      if (session) {
        return authorizedFetch(path, init, { ...options, retryAuth: false });
      }
    }
    notifyAuthExpired(ownedSessionId);
  }
  return response;
}

async function requestSession(path: string): Promise<AuthResponse> {
  const response = await fetch(`${API_PREFIX}${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  const payload = (await response.json().catch(() => null)) as ApiEnvelope<AuthResponse> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.message || `session request failed (${response.status})`);
  }
  return payload.data;
}

async function requestRefreshWithBrowserLock(): Promise<AuthResponse> {
  return requestWithBrowserAuthLock(() => requestSession("/api/auth/refresh"));
}

async function requestWithBrowserAuthLock<T>(request: () => Promise<T>): Promise<T> {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request("jd-rag-refresh-token", request);
  }
  return request();
}

async function requestLogoutWithBrowserLock(): Promise<void> {
  const request = async () => {
    await fetch(`${API_PREFIX}/api/auth/logout`, {
      method: "POST",
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
  };
  if (typeof navigator !== "undefined" && navigator.locks) {
    await navigator.locks.request("jd-rag-refresh-token", request);
    return;
  }
  if (refreshPromise) await refreshPromise;
  await request();
}

/**
 * @param expectedSessionId the session the caller was serving, after absorbing
 * any bump a failed refresh caused. Omit for callers not tied to one request.
 */
function notifyAuthExpired(expectedSessionId?: number) {
  if (expectedSessionId !== undefined && expectedSessionId !== authSessionId) {
    // A late 401 belonging to an abandoned session must never sign out the
    // session that is live now, nor tell the page to drop it.
    return;
  }
  // Clearing is idempotent: an already-empty session is not cleared again, so
  // concurrent 401s sharing one failed refresh do not each advance the ids and
  // make later ones look like an account switch. The event still fires for
  // every caller, since it is only a notification.
  if (accessToken) clearAuthSession();
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
  }
}

function removeLegacyAuthStorage() {
  if (typeof window === "undefined") return;
  localStorage.removeItem("jd-rag-token");
  localStorage.removeItem("jd-rag-user");
}

/** Parse JSON array or NDJSON / multi-object text into JobDraft list for bulk import. */
export function parseJobImportPayload(raw: string): JobDraft[] {
  const text = raw.trim();
  if (!text) throw new Error("导入内容为空");

  // Single JSON array or { items: [...] }
  try {
    const parsed = JSON.parse(text) as unknown;
    if (Array.isArray(parsed)) return parsed.map(normalizeJobDraft);
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { items?: unknown }).items)) {
      return ((parsed as { items: unknown[] }).items).map(normalizeJobDraft);
    }
    if (parsed && typeof parsed === "object") {
      return [normalizeJobDraft(parsed)];
    }
  } catch (error) {
    // Keep validation errors from normalizeJobDraft; only fall through on JSON syntax issues.
    if (error instanceof Error && !error.message.startsWith("Unexpected") && !/JSON/i.test(error.message)) {
      throw error;
    }
  }

  // NDJSON: one object per line
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (lines.length > 1) {
    const objects: JobDraft[] = [];
    for (const line of lines) {
      try {
        objects.push(normalizeJobDraft(JSON.parse(line)));
      } catch {
        throw new Error(`无法解析行：${line.slice(0, 80)}`);
      }
    }
    return objects;
  }

  throw new Error("请粘贴 JSON 数组，例如 [{\"title\":\"...\",\"companyName\":\"...\",\"description\":\"...\"}]");
}

function normalizeJobDraft(value: unknown): JobDraft {
  if (!value || typeof value !== "object") {
    throw new Error("每条 JD 必须是对象");
  }
  const item = value as Record<string, unknown>;
  const title = String(item.title ?? "").trim();
  const companyName = String(item.companyName ?? "").trim();
  const description = String(item.description ?? "").trim();
  if (!title || !companyName || !description) {
    throw new Error("每条 JD 需要 title、companyName、description");
  }
  return {
    title,
    companyName,
    location: item.location != null ? String(item.location) : "",
    employmentType: item.employmentType != null ? String(item.employmentType) : "全职",
    description,
    requirements: item.requirements != null ? String(item.requirements) : "",
  };
}

export const SAMPLE_BULK_JOBS: JobDraft[] = [
  {
    title: "Java 后端工程师",
    companyName: "示例科技",
    location: "杭州",
    employmentType: "全职",
    description: "负责招聘业务后端服务开发与维护，参与接口设计与性能优化。",
    requirements: "Java、Spring Boot、MySQL；了解 JWT 与 REST。",
  },
  {
    title: "RAG 应用工程师",
    companyName: "示例智能",
    location: "远程",
    employmentType: "全职",
    description: "建设企业内部知识库 RAG 链路，完成分块、检索与生成式问答。",
    requirements: "Embedding、向量检索、Python 或 Java；加分 LLM 对接经验。",
  },
];
