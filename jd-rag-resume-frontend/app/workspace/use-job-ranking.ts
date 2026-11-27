import { useCallback, useEffect, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { mergeLatestAnalysisSummaries } from "../analysis-poll";
import {
  apiRequest,
  visibleApiErrorMessage,
  type AnalysisSummary,
  type Job,
  type JobSemanticMatch,
  type PageData,
} from "../lib/api";
import {
  DEFAULT_JOB_SORT,
  requestSemanticMatches,
  shouldAutoLoadSemanticMatches,
} from "../semantic-ranking";
import {
  JOB_PAGE_SIZE,
  computeJobLibraryView,
  type JobAnalysesStatus,
  type JobFilter,
  type JobSemanticStatus,
  type JobSort,
} from "./workspace-helpers";

type UseJobRankingParams = {
  token: string;
  selectedResumeId: number | "";
  resumeScopeEpoch: number;
  jobs: Job[];
  jobsTotal: number;
  jobsPage: number;
  setJobs: Dispatch<SetStateAction<Job[]>>;
  setJobsTotal: Dispatch<SetStateAction<number>>;
  setJobsPage: Dispatch<SetStateAction<number>>;
  setError: Dispatch<SetStateAction<string>>;
  reportRequestError: (reason: unknown, fallback: string) => void;
};

export function useJobRanking({
  token,
  selectedResumeId,
  resumeScopeEpoch,
  jobs,
  jobsTotal,
  jobsPage,
  setJobs,
  setJobsTotal,
  setJobsPage,
  setError,
  reportRequestError,
}: UseJobRankingParams) {
  const [jobsLoadingMore, setJobsLoadingMore] = useState(false);
  const [jobSort, setJobSort] = useState<JobSort>(DEFAULT_JOB_SORT);
  const [jobFilter, setJobFilter] = useState<JobFilter>("all");
  const [jobLatestAnalyses, setJobLatestAnalyses] = useState<AnalysisSummary[]>([]);
  const [jobAnalysesResumeId, setJobAnalysesResumeId] = useState<number | null>(null);
  const [jobAnalysesStatus, setJobAnalysesStatus] = useState<JobAnalysesStatus>("idle");
  const [jobAnalysesError, setJobAnalysesError] = useState("");
  const [jobSemanticMatches, setJobSemanticMatches] = useState<JobSemanticMatch[]>([]);
  const [jobSemanticResumeId, setJobSemanticResumeId] = useState<number | null>(null);
  const [jobSemanticStatus, setJobSemanticStatus] = useState<JobSemanticStatus>("idle");
  const [jobSemanticError, setJobSemanticError] = useState("");
  const [topMatchCount, setTopMatchCount] = useState(5);

  const jobAnalysesRequestGeneration = useRef(0);
  const jobSemanticRequestGeneration = useRef(0);

  const invalidateSemanticMatches = useCallback(() => {
    jobSemanticRequestGeneration.current += 1;
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
  }, []);

  const resetForResumeChange = useCallback((nextResumeId: number | "") => {
    jobAnalysesRequestGeneration.current += 1;
    jobSemanticRequestGeneration.current += 1;
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus(nextResumeId ? "loading" : "idle");
    setJobAnalysesError("");
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
  }, []);

  const resetAllRanking = useCallback(() => {
    jobAnalysesRequestGeneration.current += 1;
    jobSemanticRequestGeneration.current += 1;
    setJobSort(DEFAULT_JOB_SORT);
    setJobFilter("all");
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus("idle");
    setJobAnalysesError("");
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
  }, []);

  const loadJobAnalyses = useCallback(async (resumeId: number) => {
    const generation = ++jobAnalysesRequestGeneration.current;
    await Promise.resolve();
    if (generation !== jobAnalysesRequestGeneration.current) return;
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus("loading");
    setJobAnalysesError("");
    try {
      const items = await apiRequest<AnalysisSummary[]>(
        `/api/analysis-histories/latest-by-resume?resumeId=${resumeId}`,
      );
      if (generation !== jobAnalysesRequestGeneration.current) return;
      setJobLatestAnalyses((current) => mergeLatestAnalysisSummaries(items, current));
      setJobAnalysesResumeId(resumeId);
      setJobAnalysesStatus("ready");
    } catch (reason) {
      if (generation !== jobAnalysesRequestGeneration.current) return;
      const message = visibleApiErrorMessage(reason, "无法读取职位匹配分");
      if (message == null) return;
      setJobAnalysesResumeId(resumeId);
      setJobAnalysesStatus("error");
      setJobAnalysesError(message);
      setError(message);
    }
  }, [setError]);

  const loadSemanticMatches = useCallback(async (resumeId: number) => {
    const generation = ++jobSemanticRequestGeneration.current;
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("loading");
    setJobSemanticError("");
    try {
      const items = await requestSemanticMatches(resumeId, apiRequest);
      if (generation !== jobSemanticRequestGeneration.current) return null;
      setJobSemanticMatches(items);
      setJobSemanticResumeId(resumeId);
      setJobSemanticStatus("ready");
      // The match endpoint always covers the complete per-user library (maximum 200).
      setJobs(items.map((item) => item.job));
      setJobsTotal(items.length);
      setJobsPage(Math.max(0, Math.ceil(items.length / JOB_PAGE_SIZE) - 1));
      return items;
    } catch (reason) {
      if (generation !== jobSemanticRequestGeneration.current) return null;
      const message = visibleApiErrorMessage(reason, "无法完成职位向量粗排");
      if (message == null) return null;
      setJobSemanticResumeId(resumeId);
      setJobSemanticStatus("error");
      setJobSemanticError(message);
      setError(message);
      return null;
    }
  }, [setError, setJobs, setJobsPage, setJobsTotal]);

  useEffect(() => {
    if (!token || !selectedResumeId) return;
    const timer = window.setTimeout(() => void loadJobAnalyses(selectedResumeId), 0);
    return () => window.clearTimeout(timer);
  }, [loadJobAnalyses, resumeScopeEpoch, selectedResumeId, token]);

  useEffect(() => {
    if (!shouldAutoLoadSemanticMatches({
      hasToken: Boolean(token),
      selectedResumeId,
      jobSort,
      jobSemanticResumeId,
      jobSemanticStatus,
    })) return;
    const timer = window.setTimeout(() => void loadSemanticMatches(Number(selectedResumeId)), 0);
    return () => window.clearTimeout(timer);
  }, [jobSemanticResumeId, jobSemanticStatus, jobSort, loadSemanticMatches, selectedResumeId, token]);

  const loadMoreJobs = useCallback(async () => {
    if (jobsLoadingMore || jobs.length >= jobsTotal) return;
    setJobsLoadingMore(true);
    setError("");
    try {
      const nextPage = jobsPage + 1;
      const page = await apiRequest<PageData<Job>>(
        `/api/job-descriptions?page=${nextPage}&size=${JOB_PAGE_SIZE}`,
      );
      setJobs((items) => {
        const seen = new Set(items.map((item) => item.id));
        return [...items, ...page.content.filter((item) => !seen.has(item.id))];
      });
      setJobsTotal(page.totalElements);
      setJobsPage(nextPage);
    } catch (reason) {
      reportRequestError(reason, "无法加载更多职位");
    } finally {
      setJobsLoadingMore(false);
    }
  }, [jobs.length, jobsLoadingMore, jobsPage, jobsTotal, reportRequestError, setError, setJobs, setJobsPage, setJobsTotal]);

  const loadAllJobsForRanking = useCallback(async () => {
    if (jobsLoadingMore || jobs.length >= jobsTotal) return;
    setJobsLoadingMore(true);
    setError("");
    try {
      const pageCount = Math.ceil(jobsTotal / JOB_PAGE_SIZE);
      const pages = await Promise.all(
        Array.from({ length: pageCount }, (_, page) =>
          apiRequest<PageData<Job>>(`/api/job-descriptions?page=${page}&size=${JOB_PAGE_SIZE}`),
        ),
      );
      const seen = new Set<number>();
      setJobs(pages.flatMap((page) => page.content).filter((item) => {
        if (seen.has(item.id)) return false;
        seen.add(item.id);
        return true;
      }));
      setJobsTotal(pages[0]?.totalElements ?? jobsTotal);
      setJobsPage(Math.max(0, pageCount - 1));
    } catch (reason) {
      reportRequestError(reason, "无法加载完整职位库");
    } finally {
      setJobsLoadingMore(false);
    }
  }, [jobs.length, jobsLoadingMore, jobsTotal, reportRequestError, setError, setJobs, setJobsPage, setJobsTotal]);

  const view = computeJobLibraryView({
    jobs,
    selectedResumeId,
    jobSort,
    jobFilter,
    jobLatestAnalyses,
    jobAnalysesStatus,
    jobAnalysesResumeId,
    jobSemanticMatches,
    jobSemanticStatus,
    jobSemanticResumeId,
  });

  return {
    jobsLoadingMore,
    jobSort,
    setJobSort,
    jobFilter,
    setJobFilter,
    jobLatestAnalyses,
    setJobLatestAnalyses,
    jobAnalysesStatus,
    setJobAnalysesStatus,
    jobAnalysesResumeId,
    setJobAnalysesResumeId,
    jobAnalysesError,
    jobSemanticMatches,
    jobSemanticStatus,
    jobSemanticError,
    topMatchCount,
    setTopMatchCount,
    loadJobAnalyses,
    loadSemanticMatches,
    loadMoreJobs,
    loadAllJobsForRanking,
    invalidateSemanticMatches,
    resetForResumeChange,
    resetAllRanking,
    ...view,
  };
}
