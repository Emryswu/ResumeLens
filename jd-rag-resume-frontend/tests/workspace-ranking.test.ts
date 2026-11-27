import assert from "node:assert/strict";
import test from "node:test";
import type { AnalysisSummary, Job } from "../app/lib/api.ts";
import {
  computeJobLibraryView,
  jobSortShortLabel,
} from "../app/workspace/workspace-helpers.ts";

function makeJob(id: number, createdAt: string): Job {
  return {
    id,
    title: `Job ${id}`,
    companyName: "Company",
    description: "Description",
    createdAt,
  };
}

function makeAnalysis(id: number, jobDescriptionId: number, status: AnalysisSummary["status"], matchScore: number | null, createdAt: string): AnalysisSummary {
  return {
    id,
    resumeId: 1,
    jobDescriptionId,
    status,
    matchScore,
    createdAt,
  };
}

test("jobSortShortLabel returns display labels for all sort modes", () => {
  assert.equal(jobSortShortLabel("semantic"), "按语义相关度");
  assert.equal(jobSortShortLabel("score"), "按匹配分");
  assert.equal(jobSortShortLabel("analyzed"), "按最近分析");
  assert.equal(jobSortShortLabel("recent"), "按最近保存");
});

test("computeJobLibraryView falls back to recent sort while semantic or analysis data is not ready", () => {
  const jobs = [
    makeJob(1, "2026-10-01T00:00:00Z"),
    makeJob(2, "2026-10-03T00:00:00Z"),
  ];
  const view = computeJobLibraryView({
    jobs,
    selectedResumeId: 1,
    jobSort: "semantic",
    jobFilter: "all",
    jobLatestAnalyses: [],
    jobAnalysesStatus: "loading",
    jobAnalysesResumeId: null,
    jobSemanticMatches: [],
    jobSemanticStatus: "loading",
    jobSemanticResumeId: null,
  });

  assert.equal(view.libraryRankingReady, false);
  assert.deepEqual(view.visibleJobs, []);
  assert.deepEqual(view.libraryJobs.map((j) => j.id), [2, 1]);
});

test("computeJobLibraryView sorts by semantic similarity and filters unanalyzed jobs", () => {
  const jobs = [
    makeJob(1, "2026-10-01T00:00:00Z"),
    makeJob(2, "2026-10-02T00:00:00Z"),
    makeJob(3, "2026-10-03T00:00:00Z"),
  ];
  const semanticView = computeJobLibraryView({
    jobs,
    selectedResumeId: 1,
    jobSort: "semantic",
    jobFilter: "all",
    jobLatestAnalyses: [],
    jobAnalysesStatus: "ready",
    jobAnalysesResumeId: 1,
    jobSemanticMatches: [
      { job: jobs[0], similarity: 0.91 },
      { job: jobs[2], similarity: 0.72 },
      { job: jobs[1], similarity: 0.55 },
    ],
    jobSemanticStatus: "ready",
    jobSemanticResumeId: 1,
  });

  assert.equal(semanticView.libraryRankingReady, true);
  assert.deepEqual(semanticView.libraryJobs.map((j) => j.id), [1, 3, 2]);

  const unanalyzedView = computeJobLibraryView({
    jobs,
    selectedResumeId: 1,
    jobSort: "score",
    jobFilter: "unanalyzed",
    jobLatestAnalyses: [
      makeAnalysis(10, 1, "COMPLETED", 88, "2026-10-04T00:00:00Z"),
      makeAnalysis(11, 2, "FAILED", null, "2026-10-05T00:00:00Z"),
    ],
    jobAnalysesStatus: "ready",
    jobAnalysesResumeId: 1,
    jobSemanticMatches: [],
    jobSemanticStatus: "idle",
    jobSemanticResumeId: null,
  });

  assert.equal(unanalyzedView.libraryRankingReady, true);
  assert.deepEqual(unanalyzedView.libraryJobs.map((j) => j.id), [3, 2]);
});
