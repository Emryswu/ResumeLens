import assert from "node:assert/strict";
import test from "node:test";

import { loadState, reportPanelState } from "../app/view-states.ts";

test("nothing loaded yet is loading, not empty; a failure with nothing to show is an error", () => {
  assert.equal(loadState({ hasData: false, loading: true, error: "" }), "loading");
  assert.equal(loadState({ hasData: false, loading: false, error: "无法连接后端" }), "error");
  assert.equal(loadState({ hasData: false, loading: false, error: "" }), "ready");
});

test("a failed refresh keeps the data on screen instead of replacing it with an error", () => {
  assert.equal(loadState({ hasData: true, loading: false, error: "无法连接后端" }), "ready");
  assert.equal(loadState({ hasData: true, loading: true, error: "" }), "ready");
});

const base = {
  workspace: "ready" as const,
  analysis: null,
  analyzing: false,
  fetchingReport: false,
  fetchError: false,
  resumeCount: 1,
  jobCount: 1,
};

test("the report panel waits for the first workspace load before claiming the library is empty", () => {
  assert.equal(reportPanelState({ ...base, workspace: "loading", resumeCount: 0, jobCount: 0 }), "workspace-loading");
  assert.equal(reportPanelState({ ...base, resumeCount: 0, jobCount: 0 }), "no-resume");
  assert.equal(reportPanelState({ ...base, jobCount: 0 }), "no-job");
  assert.equal(reportPanelState(base), "ready");
});

test("a completed report wins unless a new analysis of it is running", () => {
  const completed = { status: "COMPLETED" as const };
  assert.equal(reportPanelState({ ...base, analysis: completed }), "report");
  assert.equal(reportPanelState({ ...base, analysis: completed, workspace: "loading" }), "report");
  assert.equal(reportPanelState({ ...base, analysis: completed, analyzing: true }), "analyzing");
});

test("running, reading and failing each get their own panel, in that order", () => {
  assert.equal(reportPanelState({ ...base, analysis: { status: "PENDING" } }), "analyzing");
  assert.equal(reportPanelState({ ...base, fetchingReport: true, fetchError: true }), "fetching");
  assert.equal(reportPanelState({ ...base, fetchError: true }), "fetch-error");
  assert.equal(reportPanelState({ ...base, analysis: { status: "FAILED" } }), "failed");
  // A failed analysis is still shown when the library has since been emptied, so its retry stays reachable.
  assert.equal(reportPanelState({ ...base, analysis: { status: "FAILED" }, resumeCount: 0 }), "failed");
});
