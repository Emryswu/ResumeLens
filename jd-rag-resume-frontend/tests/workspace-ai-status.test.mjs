import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import vm from "node:vm";
import test from "node:test";
import ts from "typescript";

const workspaceFile = fileURLToPath(new URL("../app/workspace/page.tsx", import.meta.url));
const workspaceSource = readFileSync(workspaceFile, "utf8");
const workspaceAst = ts.createSourceFile(workspaceFile, workspaceSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);

function walk(node, visitor) {
  visitor(node);
  ts.forEachChild(node, (child) => walk(child, visitor));
}

test("no AI status request anywhere in frontend app disables authentication", () => {
  const folder = fileURLToPath(new URL("../app/", import.meta.url));
  const pending = [folder];
  let checked = 0;
  while (pending.length) {
    const directory = pending.pop();
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const filename = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        pending.push(filename);
      } else if (/\.tsx?$/.test(entry.name)) {
        const source = readFileSync(filename, "utf8");
        const ast = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true,
          entry.name.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
        walk(ast, (node) => {
          if (!ts.isCallExpression(node)) return;
          if (!node.arguments.some((arg) => arg.getText(ast).includes("/api/ai/status"))) return;
          checked++;
          assert.doesNotMatch(node.getText(ast), /\bauth\s*:\s*false\b/,
            filename + " must use the logged-in session for /api/ai/status");
        });
      }
    }
  }
  assert.ok(checked >= 2, "expected status requests in home and workspace");
});

function actualWorkspaceHandlers(context) {
  let effect;
  let login;
  let helper;
  walk(workspaceAst, (node) => {
    if (ts.isCallExpression(node)
        && node.expression.getText(workspaceAst) === "useEffect"
        && node.arguments[0]?.getText(workspaceAst).includes("refreshSession()")) {
      effect = node.arguments[0].getText(workspaceAst);
    }
    if (ts.isFunctionDeclaration(node) && node.name?.text === "submitAuth") {
      login = node.getText(workspaceAst);
    }
    if (ts.isFunctionDeclaration(node) && node.name?.text === "loadAiStatus") {
      helper = node.getText(workspaceAst);
    }
  });
  assert.ok(effect, "workspace bootstrap effect not found");
  assert.ok(login, "workspace login handler not found");
  const javascript = ts.transpileModule(
    [helper || "", "const workspaceEffect = " + effect + ";", login].join("\n"),
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }
  ).outputText;
  return vm.runInNewContext(javascript + "\n({ workspaceEffect, submitAuth })", context);
}

function harness() {
  let authorized = false;
  let resolveRefresh;
  let latestStatus = null;
  const status = {
    mockEnabled: false,
    model: "test-model",
    pendingTimeoutMinutes: 37,
  };
  const calls = [];
  const noOp = () => {};
  const context = {
    window: {
      location: { search: "" }, setTimeout: () => 1, clearTimeout: noOp,
      addEventListener: noOp, removeEventListener: noOp,
    },
    URLSearchParams, AUTH_EXPIRED_EVENT: "auth-expired",
    chooseResume: noOp, chooseJob: noOp, clearAuthenticatedView: noOp,
    setError: noOp, setToken: noOp, setUser: noOp, setNotice: noOp,
    setBusy: noOp, setAnalysis: noOp, setMatchMode: noOp,
    reportRequestError: (error) => { throw error; },
    getAuthSessionId: () => 4,
    isAuthSessionCurrent: (session) => session === 4,
    loadWorkspace: async () => {},
    setAiStatus: (next) => { latestStatus = next; },
    refreshSession: () => new Promise((resolve) => { resolveRefresh = resolve; }),
    signInRequest: async () => ({ accessToken: "valid-token", user: { id: 7, displayName: "Arthur" } }),
    setAccessToken: () => { authorized = true; },
    apiRequest: async (url, request, options) => {
      if (url !== "/api/ai/status") throw new Error("unexpected request: " + url);
      calls.push({ url, auth: options?.auth });
      if (!authorized || options?.auth === false) throw new Error("401");
      return status;
    },
    authMode: "login",
    auth: { username: "Arthur", password: "test" },
  };
  return {
    context, calls,
    restore() {
      assert.ok(resolveRefresh, "the effect must call refreshSession()");
      authorized = true;
      resolveRefresh({ accessToken: "valid-token", user: { id: 7, displayName: "Arthur" } });
    },
    get latestStatus() { return latestStatus; },
    expected: status,
  };
}

async function flushRequests() {
  await new Promise((resolve) => setImmediate(resolve));
}

test("workspace restores login before reading AI status and receives runtime/timeout settings", async () => {
  const app = harness();
  const { workspaceEffect } = actualWorkspaceHandlers(app.context);
  const cleanup = workspaceEffect();
  assert.equal(app.calls.length, 0, "do not request auth-only status before refresh succeeds");
  app.restore();
  await flushRequests();
  assert.equal(app.calls.length, 1);
  assert.equal(app.calls[0].auth, undefined);
  assert.deepEqual(JSON.parse(JSON.stringify(app.latestStatus)), app.expected);
  cleanup();
});

test("workspace direct sign-in also receives AI status with its access token", async () => {
  const app = harness();
  const { submitAuth } = actualWorkspaceHandlers(app.context);
  await submitAuth({ preventDefault() {} });
  await flushRequests();
  assert.equal(app.calls.length, 1);
  assert.equal(app.calls[0].auth, undefined);
  assert.deepEqual(JSON.parse(JSON.stringify(app.latestStatus)), app.expected);
});
