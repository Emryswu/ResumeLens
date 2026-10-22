import assert from "node:assert/strict";
import test from "node:test";

import { SAMPLE_JOB, SAMPLE_RESUME, saveSampleData, submitAnalysis } from "../app/sample-data.ts";
import { signInRequest } from "../app/sign-in.ts";

type Call = { path: string; init?: RequestInit; options?: unknown };

/** Answers each request with the next canned response and records what was sent. */
function fakeRequest(responses: unknown[]) {
  const calls: Call[] = [];
  const request = async <T,>(path: string, init?: RequestInit, options?: unknown): Promise<T> => {
    calls.push({ path, init, options });
    return responses.shift() as T;
  };
  return { calls, request };
}

test("示例数据先存简历再存职位，用的是和工作台同一份示例", async () => {
  const { calls, request } = fakeRequest([{ id: 11 }, { id: 22 }]);
  const saved = await saveSampleData(request);
  assert.deepEqual(saved, { resume: { id: 11 }, job: { id: 22 } });
  assert.deepEqual(calls.map((call) => [call.path, call.init?.method]), [["/api/resumes", "POST"], ["/api/job-descriptions", "POST"]]);
  assert.deepEqual(JSON.parse(String(calls[0].init?.body)), SAMPLE_RESUME);
  assert.deepEqual(JSON.parse(String(calls[1].init?.body)), SAMPLE_JOB);
});

test("示例简历保存失败时不再存职位", async () => {
  const calls: string[] = [];
  const failing = async <T,>(path: string): Promise<T> => {
    calls.push(path);
    throw new Error("quota");
  };
  await assert.rejects(saveSampleData(failing), /quota/);
  assert.deepEqual(calls, ["/api/resumes"]);
});

test("发起分析只提交这一对简历和职位", async () => {
  const { calls, request } = fakeRequest([{ id: 5, status: "PENDING" }]);
  await submitAnalysis(request, 11, 22);
  assert.equal(calls[0].path, "/api/analysis-histories/ai");
  assert.deepEqual(JSON.parse(String(calls[0].init?.body)), { resumeId: 11, jobDescriptionId: 22 });
});

test("登录只发用户名和密码，注册发整张表；都不带旧 token", async () => {
  const form = { username: "u", password: "p", email: "e@example.com", displayName: "D" };
  const { calls, request } = fakeRequest([{}, {}]);
  await signInRequest(request, "login", form);
  await signInRequest(request, "register", form);
  assert.equal(calls[0].path, "/api/auth/login");
  assert.deepEqual(JSON.parse(String(calls[0].init?.body)), { username: "u", password: "p" });
  assert.equal(calls[1].path, "/api/auth/register");
  assert.deepEqual(JSON.parse(String(calls[1].init?.body)), form);
  assert.deepEqual(calls.map((call) => call.options), [{ auth: false }, { auth: false }]);
});
