"use client";

import { ArrowRight, ArrowUpRight, Check, CircleAlert, CircleCheck, LoaderCircle, Plus, X } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import { AppShell } from "./components/AppShell";
import { BackendStatus } from "./components/BackendStatus";
import { MatchReport } from "./components/MatchReport";
import { bookmarkKey, parseBookmarks } from "./workspace-bookmarks";
import { hashForView, navLabel, viewFromHash, type HashView } from "./workspace-nav";
import {
  AUTH_EXPIRED_EVENT,
  SAMPLE_BULK_JOBS,
  type Analysis,
  type AnalysisSummary,
  type AiStatus,
  type AuthResponse,
  type Job,
  type JobSemanticMatch,
  type PageData,
  type Resume,
  type User,
  apiRequest,
  getAuthSessionId,
  isAuthSessionCurrent,
  logoutSession,
  parseJobImportPayload,
  refreshSession,
  setAccessToken,
  visibleApiErrorMessage,
} from "./lib/api";
import {
  buildReportMarkdown,
  buildReportPrintHtml,
  downloadTextFile,
  openPrintableReport,
  reportFilename,
} from "./report-export";
import {
  analysisPollTimeoutMs,
  mergeLatestAnalysisSummaries,
  pollAnalysisUntilSettled,
} from "./analysis-poll";
import { appendResumeUploadFields, prepareResumeUploadDraft, resumeFormFrom } from "./resume-upload";
import {
  DEFAULT_JOB_SORT,
  requestSemanticMatches,
  semanticAnalysisTargets,
  shouldAutoLoadSemanticMatches,
} from "./semantic-ranking";

const EMPTY_RESUME_FORM = {
  title: "",
  candidateName: "",
  phone: "",
  email: "",
  rawText: "",
};
const EMPTY_JOB_FORM = {
  title: "",
  companyName: "",
  location: "",
  employmentType: "全职",
  description: "",
  requirements: "",
};
const HISTORY_PREVIEW_LIMIT = 8;
const HISTORY_PAGE_SIZE = 20;
const JOB_PAGE_SIZE = 50;
type JobSort = "recent" | "semantic" | "score" | "analyzed";
type JobFilter = "all" | "unanalyzed";
type JobAnalysesStatus = "idle" | "loading" | "ready" | "error";
type JobSemanticStatus = "idle" | "loading" | "ready" | "error";
type AnalysisRun = { generation: number; resumeId: number; jobId: number };

const VIEW_SUBTITLES: Record<HashView, string> = {
  match: "选一份简历和目标岗位，查看匹配报告和简历里的原文证据。",
  resumes: "上传或粘贴你的简历，保存后即可用于岗位匹配。",
  jobs: "录入目标岗位的职位描述，也可以批量导入。",
  saved: "收藏保存在当前浏览器，按账号区分；不会跨设备同步。",
  history: "你在当前账号下做过的每一次分析。",
};

function analysisTimestamp(item?: AnalysisSummary) {
  const timestamp = item?.createdAt ? Date.parse(item.createdAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : -1;
}

function analysisScore(item?: AnalysisSummary) {
  const score = Number(item?.matchScore);
  return item?.status === "COMPLETED" && Number.isFinite(score) ? score : -1;
}

function jobScoreLabel(item?: AnalysisSummary) {
  if (!item) return "未分析";
  if (item.status === "PENDING") return "分析中";
  if (item.status === "FAILED") return "失败";
  const score = Number(item.matchScore);
  return Number.isFinite(score) ? `${score.toFixed(0)} 分` : "—";
}

function jobTimestamp(item: Job) {
  const timestamp = item.createdAt ? Date.parse(item.createdAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : -1;
}

const SAMPLE_RESUME = {
  title: "Java 后端开发简历",
  candidateName: "张三",
  phone: "13800000000",
  email: "zhangsan@example.com",
  rawText: `技能
熟练掌握 Java、Spring Boot、MySQL、JWT 与 REST API 开发；有权限体系与全局异常处理实践。

工作经历
2023-2025 某互联网公司后端开发：维护招聘业务服务，设计简历/职位表结构，完成用户数据隔离；参与登录鉴权改造，引入 JWT。

项目经历
负责简历匹配系统中的 RAG 模块：文本分块、本地 Embedding、Top-K 召回与证据拼装，支持中英文；对接 DeepSeek Chat Completions，约束 JSON 输出。
使用 Apache Tika 解析 PDF/DOCX 简历文本，并提供 rawText 校对入口。

教育
本科 · 计算机科学与技术 · 主修数据结构、操作系统、计算机网络。`,
};

const SAMPLE_JOB = {
  title: "RAG 平台工程师",
  companyName: "某科技",
  location: "杭州",
  employmentType: "全职",
  description: "负责企业知识库与招聘场景的 RAG 链路建设，覆盖解析、分块、向量检索与生成式分析，和业务一起把匹配结果做得可解释。",
  requirements: "Java / Spring Boot / MySQL；熟悉 Embedding、向量检索；有 JWT/权限与 API 设计经验；加分：DeepSeek/OpenAI 对接、简历解析。",
};

function configuredModel(status: AiStatus | null) {
  return status?.model.trim() || "模型未配置";
}

function runtimeModeLabel(status: AiStatus | null) {
  if (!status) return "AI 运行模式确认中";
  return status.mockEnabled
    ? "演示模式（离线 mock），未调用真实模型"
    : `当前生成模型：${configuredModel(status)}`;
}

function generationProgressLabel(status: AiStatus | null) {
  if (!status) return "生成模式确认中，正在准备分析…";
  return status.mockEnabled
    ? "演示模式（离线 mock），未调用真实模型"
    : `${configuredModel(status)} 正在生成分析…`;
}

function pendingAnalysisLabel(status: AiStatus | null) {
  if (!status) return "本地检索与生成分析正在进行（运行模式确认中），完成后会自动更新。";
  return status.mockEnabled
    ? "本地检索与离线 mock 演示分析正在进行；演示模式（离线 mock），未调用真实模型。完成后会自动更新。"
    : `本地检索与 ${configuredModel(status)} 分析正在进行，完成后会自动更新。`;
}

export default function Home() {
  const [token, setToken] = useState("");
  const [pageView, setPageView] = useState<HashView>("match");
  const [savedJobs, setSavedJobs] = useState<number[]>([]);
  const [fetchingReport, setFetchingReport] = useState(false);
  // Bumped by every chooseResume call. Re-selecting the same resume leaves
  // selectedResumeId unchanged, so without this counter nothing would depend-change
  // to refetch the per-job scores that chooseResume just cleared.
  const [resumeScopeEpoch, setResumeScopeEpoch] = useState(0);
  const [user, setUser] = useState<User | null>(null);
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [auth, setAuth] = useState({ username: "", password: "", email: "", displayName: "" });
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [jobsTotal, setJobsTotal] = useState(0);
  const [jobsPage, setJobsPage] = useState(0);
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
  const [history, setHistory] = useState<Analysis[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyExpanded, setHistoryExpanded] = useState(false);
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false);
  const [selectedResumeId, setSelectedResumeId] = useState<number | "">("");
  const [selectedJobId, setSelectedJobId] = useState<number | "">("");
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [resumeForm, setResumeForm] = useState({ ...EMPTY_RESUME_FORM });
  const [jobForm, setJobForm] = useState({ ...EMPTY_JOB_FORM });
  const [editingResumeId, setEditingResumeId] = useState<number | null>(null);
  const [editingJobId, setEditingJobId] = useState<number | null>(null);
  const [bulkImportText, setBulkImportText] = useState(() => JSON.stringify(SAMPLE_BULK_JOBS, null, 2));
  const [showBulkImport, setShowBulkImport] = useState(false);
  const [aiStatus, setAiStatus] = useState<AiStatus | null>(null);
  const analysisRunSequence = useRef(0);
  const activeAnalysisRun = useRef<AnalysisRun | null>(null);
  const jobAnalysesRequestGeneration = useRef(0);
  const jobSemanticRequestGeneration = useRef(0);

  function reportRequestError(reason: unknown, fallback: string) {
    const message = visibleApiErrorMessage(reason, fallback);
    if (message == null) return;
    setError(message);
  }

  const chooseResume = useCallback((next: number | "") => {
    setFetchingReport(false);
    const run = activeAnalysisRun.current;
    if (run && run.resumeId !== next) activeAnalysisRun.current = null;
    jobAnalysesRequestGeneration.current += 1;
    jobSemanticRequestGeneration.current += 1;
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus(next ? "loading" : "idle");
    setJobAnalysesError("");
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
    setAnalysis((current) => current && current.resumeId !== next ? null : current);
    setResumeScopeEpoch((value) => value + 1);
    setSelectedResumeId(next);
  }, []);

  const chooseJob = useCallback((next: number | "") => {
    setFetchingReport(false);
    const run = activeAnalysisRun.current;
    if (run && run.jobId !== next) activeAnalysisRun.current = null;
    setAnalysis((current) => current && current.jobDescriptionId !== next ? null : current);
    setSelectedJobId(next);
  }, []);

  function clearAuthenticatedView() {
    activeAnalysisRun.current = null;
    jobAnalysesRequestGeneration.current += 1;
    jobSemanticRequestGeneration.current += 1;
    setToken("");
    setUser(null);
    setSavedJobs([]);
    setFetchingReport(false);
    setAnalysis(null);
    setResumes([]);
    setJobs([]);
    setJobsTotal(0);
    setJobsPage(0);
    setJobSort(DEFAULT_JOB_SORT);
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus("idle");
    setJobAnalysesError("");
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
    setHistory([]);
    setHistoryTotal(0);
    setHistoryPage(0);
    setHistoryExpanded(false);
    setSelectedResumeId("");
    setSelectedJobId("");
    setEditingResumeId(null);
    setEditingJobId(null);
    setFile(null);
    setResumeForm({ ...EMPTY_RESUME_FORM });
    setJobForm({ ...EMPTY_JOB_FORM });
    setAuth({ username: "", password: "", email: "", displayName: "" });
    setBulkImportText(JSON.stringify(SAMPLE_BULK_JOBS, null, 2));
    setShowBulkImport(false);
    setNotice("");
    setBusy("");
  }

  function invalidateSemanticMatches() {
    jobSemanticRequestGeneration.current += 1;
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
  }

  function beginAnalysisRun(resumeId: number, jobId: number) {
    const run = { generation: ++analysisRunSequence.current, resumeId, jobId };
    activeAnalysisRun.current = run;
    return run;
  }

  function isCurrentAnalysisRun(run: AnalysisRun) {
    return activeAnalysisRun.current?.generation === run.generation;
  }

  function acceptAnalysisProgress(next: Analysis, run: AnalysisRun) {
    if (!isCurrentAnalysisRun(run)) return;
    setAnalysis(next);
    setHistory((items) => [next, ...items.filter((item) => item.id !== next.id)]);
    setJobLatestAnalyses((items) => mergeLatestAnalysisSummaries(items, [next]));
  }

  async function waitForAnalysis(initial: Analysis, run: AnalysisRun) {
    const { analysis: current, timedOut, cancelled } = await pollAnalysisUntilSettled(initial, {
      timeoutMs: analysisPollTimeoutMs(aiStatus?.pendingTimeoutMinutes),
      fetchById: (id) => apiRequest<Analysis>(`/api/analysis-histories/${id}`),
      shouldContinue: () => isCurrentAnalysisRun(run),
      onProgress: (next) => acceptAnalysisProgress(next, run),
    });

    if (cancelled || !isCurrentAnalysisRun(run)) return null;
    activeAnalysisRun.current = null;
    if (timedOut) {
      setNotice("分析仍在后台运行，可稍后在历史记录中查看结果");
      return current;
    }
    if (current.status === "FAILED") throw new Error(current.summary || "AI 分析失败");
    return current;
  }

  const loadWorkspace = useCallback(async () => {
    const sessionId = getAuthSessionId();
    setBusy("loading");
    setError("");
    try {
      const [resumePage, jobPage, historyPage] = await Promise.all([
        apiRequest<PageData<Resume>>("/api/resumes?size=50"),
        apiRequest<PageData<Job>>(`/api/job-descriptions?page=0&size=${JOB_PAGE_SIZE}`),
        apiRequest<PageData<Analysis>>(`/api/analysis-histories?page=0&size=${HISTORY_PAGE_SIZE}`),
      ]);
      if (!isAuthSessionCurrent(sessionId)) return;
      setResumes(resumePage.content);
      setJobs(jobPage.content);
      setJobsTotal(jobPage.totalElements);
      setJobsPage(0);
      jobSemanticRequestGeneration.current += 1;
      setJobSemanticMatches([]);
      setJobSemanticResumeId(null);
      setJobSemanticStatus("idle");
      setJobSemanticError("");
      setHistory(historyPage.content);
      setHistoryTotal(historyPage.totalElements);
      setHistoryPage(0);
      if (!selectedResumeId && resumePage.content[0]) chooseResume(resumePage.content[0].id);
      if (!selectedJobId && jobPage.content[0]) chooseJob(jobPage.content[0].id);
      if (analysis) {
        const refreshed = historyPage.content.find(item => item.id === analysis.id);
        if (refreshed) setAnalysis(refreshed);
      }
    } catch (reason) {
      if (!isAuthSessionCurrent(sessionId)) return;
      reportRequestError(reason, "无法连接后端");
    } finally {
      if (isAuthSessionCurrent(sessionId)) setBusy("");
    }
  }, [analysis, chooseJob, chooseResume, selectedJobId, selectedResumeId]);

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
  }, []);

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
  }, []);

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

  async function loadMoreJobs() {
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
  }

  async function loadAllJobsForRanking() {
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
  }

  async function loadMoreHistory() {
    if (historyLoadingMore || history.length >= historyTotal) return;
    setHistoryLoadingMore(true);
    setError("");
    try {
      const nextPage = historyPage + 1;
      const page = await apiRequest<PageData<Analysis>>(
        `/api/analysis-histories?page=${nextPage}&size=${HISTORY_PAGE_SIZE}`,
      );
      setHistory((items) => {
        const seen = new Set(items.map((item) => item.id));
        return [...items, ...page.content.filter((item) => !seen.has(item.id))];
      });
      setHistoryTotal(page.totalElements);
      setHistoryPage(nextPage);
    } catch (reason) {
      reportRequestError(reason, "无法加载更多分析记录");
    } finally {
      setHistoryLoadingMore(false);
    }
  }

  useEffect(() => {
    let active = true;
    const params = new URLSearchParams(window.location.search);
    const resumeIdParam = Number(params.get("resumeId"));
    const jobIdParam = Number(params.get("jobId"));
    const analysisIdParam = Number(params.get("analysisId"));
    const locationTimer = window.setTimeout(() => {
      if (Number.isFinite(resumeIdParam) && resumeIdParam > 0) chooseResume(resumeIdParam);
      if (Number.isFinite(jobIdParam) && jobIdParam > 0) chooseJob(jobIdParam);
    }, 0);

    const handleAuthExpired = () => {
      clearAuthenticatedView();
      setError("登录已过期，请重新登录");
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    void apiRequest<AiStatus>("/api/ai/status", {}, { auth: false })
      .then((status) => {
        if (active) setAiStatus(status);
      })
      .catch(() => {
        // Keep the UI neutral when runtime mode cannot be confirmed.
      });
    void refreshSession().then(async (session) => {
      if (!session || !active) return;
      const sessionId = getAuthSessionId();
      setToken(session.accessToken);
      setUser(session.user);
      await loadWorkspace();
      if (!active || !isAuthSessionCurrent(sessionId)) return;
      if (Number.isFinite(analysisIdParam) && analysisIdParam > 0) {
        try {
          const requestedAnalysis = await apiRequest<Analysis>(`/api/analysis-histories/${analysisIdParam}`);
          if (!active || !isAuthSessionCurrent(sessionId)) return;
          chooseResume(requestedAnalysis.resumeId);
          chooseJob(requestedAnalysis.jobDescriptionId);
          setAnalysis(requestedAnalysis);
        } catch (reason) {
          if (!active || !isAuthSessionCurrent(sessionId)) return;
          reportRequestError(reason, "无法读取指定分析报告");
        }
      }
    });
    return () => {
      active = false;
      window.clearTimeout(locationTimer);
      window.removeEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function submitAuth(event: FormEvent) {
    event.preventDefault();
    setBusy("auth");
    setError("");
    try {
      const path = authMode === "login" ? "/api/auth/login" : "/api/auth/register";
      const body = authMode === "login"
        ? { username: auth.username, password: auth.password }
        : auth;
      const result = await apiRequest<AuthResponse>(
        path,
        { method: "POST", body: JSON.stringify(body) },
        { auth: false },
      );
      setAccessToken(result.accessToken);
      setToken(result.accessToken);
      setUser(result.user);
      setNotice(`欢迎回来，${result.user.displayName}`);
      await loadWorkspace();
    } catch (reason) {
      reportRequestError(reason, "登录失败");
    } finally {
      setBusy("");
    }
  }

  function logout() {
    void logoutSession();
    clearAuthenticatedView();
  }

  async function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    const nextFile = event.target.files?.[0] || null;
    setFile(nextFile);
    if (!nextFile) return;
    setResumeForm((current) => prepareResumeUploadDraft(current, nextFile.name));
  }

  async function beginEditResume(item: Resume) {
    setBusy("resume-edit");
    setError("");
    try {
      const detail = await apiRequest<Resume>(`/api/resumes/${item.id}`);
      navigate("resumes");
      setEditingResumeId(detail.id);
      setFile(null);
      setResumeForm(resumeFormFrom(detail));
      chooseResume(detail.id);
      setNotice(`正在编辑简历 #${detail.id}，保存后将更新并失效旧向量索引`);
    } catch (reason) {
      reportRequestError(reason, "加载简历失败");
    } finally {
      setBusy("");
    }
  }

  function beginEditJob(item: Job) {
    navigate("jobs");
    setEditingJobId(item.id);
    setJobForm({
      title: item.title || "",
      companyName: item.companyName || "",
      location: item.location || "",
      employmentType: item.employmentType || "全职",
      description: item.description || "",
      requirements: item.requirements || "",
    });
    chooseJob(item.id);
    setNotice(`正在编辑职位 #${item.id}`);
  }

  function resetResumeEditor() {
    setEditingResumeId(null);
    setFile(null);
    setResumeForm({ ...EMPTY_RESUME_FORM });
  }

  function resetJobEditor() {
    setEditingJobId(null);
    setJobForm({ ...EMPTY_JOB_FORM });
  }

  async function saveResume(event: FormEvent) {
    event.preventDefault();
    setBusy("resume");
    setError("");
    try {
      let saved: Resume;
      if (editingResumeId && file) {
        throw new Error("编辑模式下不支持重新上传文件；请先取消编辑，或清空文件后仅更新文本");
      }
      if (editingResumeId) {
        saved = await apiRequest<Resume>(`/api/resumes/${editingResumeId}`, {
          method: "PUT",
          body: JSON.stringify(resumeForm),
        });
        setResumes((items) => items.map((item) => (item.id === saved.id ? saved : item)));
        setNotice(`简历 #${saved.id} 已更新，下次匹配会重建向量`);
      } else if (file) {
        const form = new FormData();
        form.append("file", file);
        appendResumeUploadFields(form, resumeForm);
        saved = await apiRequest<Resume>("/api/resumes/upload", { method: "POST", body: form });
        setResumes((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setNotice("简历已上传保存，等待向量检索");
      } else {
        saved = await apiRequest<Resume>("/api/resumes", { method: "POST", body: JSON.stringify(resumeForm) });
        setResumes((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setNotice("简历已保存，等待向量检索");
      }
      chooseResume(saved.id);
      invalidateSemanticMatches();
      setEditingResumeId(null);
      setFile(null);
    } catch (reason) {
      reportRequestError(reason, "简历保存失败");
    } finally {
      setBusy("");
    }
  }

  async function saveJob(event: FormEvent) {
    event.preventDefault();
    setBusy("job");
    setError("");
    try {
      let saved: Job;
      if (editingJobId) {
        saved = await apiRequest<Job>(`/api/job-descriptions/${editingJobId}`, {
          method: "PUT",
          body: JSON.stringify(jobForm),
        });
        setJobs((items) => items.map((item) => (item.id === saved.id ? saved : item)));
        setNotice(`职位 #${saved.id} 已更新`);
      } else {
        saved = await apiRequest<Job>("/api/job-descriptions", { method: "POST", body: JSON.stringify(jobForm) });
        setJobs((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setJobsTotal((total) => total + 1);
        setNotice("职位 JD 已保存，可以开始匹配");
      }
      chooseJob(saved.id);
      invalidateSemanticMatches();
      setEditingJobId(null);
      navigate("match");
    } catch (reason) {
      reportRequestError(reason, "JD 保存失败");
    } finally {
      setBusy("");
    }
  }

  async function deleteResume(id: number) {
    if (!window.confirm(`确认删除简历 #${id}？将同时清理上传文件与向量索引。`)) return;
    setBusy("resume-delete");
    setError("");
    try {
      await apiRequest<void>(`/api/resumes/${id}`, { method: "DELETE" });
      setResumes((items) => items.filter((item) => item.id !== id));
      if (selectedResumeId === id) chooseResume("");
      if (editingResumeId === id) resetResumeEditor();
      if (analysis?.resumeId === id) setAnalysis(null);
      setNotice(`简历 #${id} 已删除`);
    } catch (reason) {
      reportRequestError(reason, "删除简历失败");
    } finally {
      setBusy("");
    }
  }

  async function deleteJob(id: number) {
    if (!window.confirm(`确认删除职位 #${id}？`)) return;
    setBusy("job-delete");
    setError("");
    try {
      await apiRequest<void>(`/api/job-descriptions/${id}`, { method: "DELETE" });
      setJobs((items) => items.filter((item) => item.id !== id));
      setJobsTotal((total) => Math.max(0, total - 1));
      if (selectedJobId === id) chooseJob("");
      if (editingJobId === id) resetJobEditor();
      if (analysis?.jobDescriptionId === id) setAnalysis(null);
      invalidateSemanticMatches();
      setNotice(`职位 #${id} 已删除`);
    } catch (reason) {
      reportRequestError(reason, "删除职位失败");
    } finally {
      setBusy("");
    }
  }

  async function bulkImportJobs() {
    setBusy("bulk-import");
    setError("");
    try {
      const items = parseJobImportPayload(bulkImportText);
      const imported = await apiRequest<Job[]>("/api/job-descriptions/import", {
        method: "POST",
        body: JSON.stringify({ items }),
      });
      setJobs((current) => {
        const merged = [...imported, ...current];
        const seen = new Set<number>();
        return merged.filter((item) => {
          if (seen.has(item.id)) return false;
          seen.add(item.id);
          return true;
        });
      });
      setJobsTotal((total) => total + imported.length);
      if (imported[0]) chooseJob(imported[0].id);
      invalidateSemanticMatches();
      setShowBulkImport(false);
      setNotice(`已批量导入 ${imported.length} 条职位（POST /api/job-descriptions/import）`);
    } catch (reason) {
      reportRequestError(reason, "批量导入失败");
    } finally {
      setBusy("");
    }
  }

  function fillSampleForms() {
    setFile(null);
    setEditingResumeId(null);
    setEditingJobId(null);
    setResumeForm({ ...SAMPLE_RESUME });
    setJobForm({ ...SAMPLE_JOB });
    setNotice("已填入示例简历与岗位，保存后即可返回岗位匹配");
  }

  function exportMarkdown() {
    if (!analysis || analysis.status !== "COMPLETED") {
      setError("请先完成一次匹配分析，再导出报告");
      return;
    }
    try {
      const markdown = buildReportMarkdown(analysis);
      downloadTextFile(reportFilename(analysis, "md"), markdown, "text/markdown;charset=utf-8");
      setNotice("已下载 Markdown 报告");
    } catch (reason) {
      reportRequestError(reason, "导出 Markdown 失败");
    }
  }

  function exportPdf() {
    if (!analysis || analysis.status !== "COMPLETED") {
      setError("请先完成一次匹配分析，再导出报告");
      return;
    }
    try {
      openPrintableReport(buildReportPrintHtml(analysis));
      setNotice("已打开打印预览：请选择「另存为 PDF」");
    } catch (reason) {
      reportRequestError(reason, "导出 PDF 失败");
    }
  }

  async function saveSampleAndAnalyze() {
    setBusy("sample");
    setError("");
    let run: AnalysisRun | null = null;
    try {
      setEditingResumeId(null);
      setEditingJobId(null);
      setFile(null);
      setResumeForm({ ...SAMPLE_RESUME });
      setJobForm({ ...SAMPLE_JOB });
      const createdResume = await apiRequest<Resume>("/api/resumes", {
        method: "POST",
        body: JSON.stringify(SAMPLE_RESUME),
      });
      const createdJob = await apiRequest<Job>("/api/job-descriptions", {
        method: "POST",
        body: JSON.stringify(SAMPLE_JOB),
      });
      setResumes((items) => [createdResume, ...items.filter((item) => item.id !== createdResume.id)]);
      setJobs((items) => [createdJob, ...items.filter((item) => item.id !== createdJob.id)]);
      setJobsTotal((total) => total + 1);
      chooseResume(createdResume.id);
      chooseJob(createdJob.id);
      setNotice("示例数据已保存，正在启动 RAG 匹配分析…");
      setBusy("analysis");
      run = beginAnalysisRun(createdResume.id, createdJob.id);
      const result = await apiRequest<Analysis>("/api/analysis-histories/ai", {
        method: "POST",
        body: JSON.stringify({ resumeId: createdResume.id, jobDescriptionId: createdJob.id }),
      });
      const completed = await waitForAnalysis(result, run);
      if (!completed) return;
      setNotice("示例匹配完成，下方报告已更新");
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (!run || isCurrentAnalysisRun(run)) {
        activeAnalysisRun.current = null;
        reportRequestError(reason, "示例流程失败");
      }
    } finally {
      setBusy("");
    }
  }

  async function runAnalysis() {
    if (!selectedResumeId || !selectedJobId) {
      setError("请先选择简历和职位 JD，或点「一键示例匹配」");
      return;
    }
    setBusy("analysis");
    setError("");
    setNotice(`正在检索（语义阈值门控 + 关键词重排），${generationProgressLabel(aiStatus)}`);
    const resumeId = Number(selectedResumeId);
    const jobId = Number(selectedJobId);
    const run = beginAnalysisRun(resumeId, jobId);
    try {
      const result = await apiRequest<Analysis>("/api/analysis-histories/ai", {
        method: "POST",
        body: JSON.stringify({ resumeId, jobDescriptionId: jobId }),
      });
      const completed = await waitForAnalysis(result, run);
      if (!completed) return;
      setNotice(completed.status === "PENDING" ? "分析仍在后台运行，可稍后刷新查看" : "分析完成，报告已保存");
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (isCurrentAnalysisRun(run)) {
        activeAnalysisRun.current = null;
        reportRequestError(reason, "AI 分析失败");
      }
    } finally {
      setBusy("");
    }
  }

  async function runTopMatchesAnalysis() {
    if (!selectedResumeId) {
      setError("请先选择一份简历，再分析向量粗排 Top N");
      return;
    }
    const resumeId = Number(selectedResumeId);
    setBusy("top-analysis");
    setError("");
    let currentRun: AnalysisRun | null = null;
    try {
      setJobSort("semantic");
      const matches = jobSemanticDataReady
        ? jobSemanticMatches
        : await loadSemanticMatches(resumeId);
      if (!matches) return;
      if (matches.length === 0) {
        setNotice("岗位库还是空的，先保存 JD 才能做向量粗排");
        return;
      }

      const latestItems = jobAnalysisDataReady
        ? jobLatestAnalyses
        : await apiRequest<AnalysisSummary[]>(`/api/analysis-histories/latest-by-resume?resumeId=${resumeId}`);
      setJobLatestAnalyses((current) => mergeLatestAnalysisSummaries(latestItems, current));
      setJobAnalysesResumeId(resumeId);
      setJobAnalysesStatus("ready");
      const candidates = matches.slice(0, topMatchCount);
      const targets = semanticAnalysisTargets(matches, latestItems, topMatchCount);
      const skipped = candidates.length - targets.length;
      if (targets.length === 0) {
        setNotice(`向量 Top ${candidates.length} 都已有当前版本的完整分析，不重复调用 LLM`);
        return;
      }

      let completedCount = 0;
      for (const [index, match] of targets.entries()) {
        chooseJob(match.job.id);
        currentRun = beginAnalysisRun(resumeId, match.job.id);
        setNotice(
          `Top N 精析 ${index + 1}/${targets.length}：${match.job.title}（粗排相似度 ${match.similarity.toFixed(3)}）`,
        );
        const submitted = await apiRequest<Analysis>("/api/analysis-histories/ai", {
          method: "POST",
          body: JSON.stringify({ resumeId, jobDescriptionId: match.job.id }),
        });
        const completed = await waitForAnalysis(submitted, currentRun);
        if (!completed) return;
        if (completed.status === "PENDING") {
          setNotice(`已完成 ${completedCount} 个；当前分析仍在后台运行，Top N 队列先暂停`);
          return;
        }
        completedCount += 1;
      }

      await loadJobAnalyses(resumeId);
      setNotice(`Top N 精析完成：新增 ${completedCount} 个，复用 ${skipped} 个已有结果`);
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (!currentRun || isCurrentAnalysisRun(currentRun)) {
        activeAnalysisRun.current = null;
        reportRequestError(reason, "Top N 分析失败");
      }
    } finally {
      setBusy("");
    }
  }

  const effectiveHistoryTotal = Math.max(historyTotal, history.length);
  const effectiveJobsTotal = Math.max(jobsTotal, jobs.length);
  const jobAnalysesLoading = jobAnalysesStatus === "loading";
  const jobAnalysisDataReady = Boolean(
    selectedResumeId
    && jobAnalysesStatus === "ready"
    && jobAnalysesResumeId === selectedResumeId,
  );
  const jobSemanticDataReady = Boolean(
    selectedResumeId
    && jobSemanticStatus === "ready"
    && jobSemanticResumeId === selectedResumeId,
  );
  const jobAnalysisDataRequired = jobSort === "score" || jobSort === "analyzed" || jobFilter === "unanalyzed";
  const jobSemanticDataRequired = jobSort === "semantic";
  const latestAnalysisByJob = new Map<number, AnalysisSummary>(jobAnalysisDataReady
    ? jobLatestAnalyses
        .filter((item) => item.resumeId === selectedResumeId)
        .map((item) => [item.jobDescriptionId, item])
    : []);
  const semanticSimilarityByJob = new Map<number, number>(jobSemanticDataReady
    ? jobSemanticMatches.map((item) => [item.job.id, item.similarity])
    : []);
  const visibleJobs = (() => {
    if (jobAnalysisDataRequired && !jobAnalysisDataReady) return [];
    if (jobSemanticDataRequired && !jobSemanticDataReady) return [];
    const filtered = jobFilter === "unanalyzed"
      ? jobs.filter((item) => {
          const latest = latestAnalysisByJob.get(item.id);
          return !latest || latest.status === "FAILED";
        })
      : [...jobs];
    if (jobSort === "recent") {
      filtered.sort((left, right) => jobTimestamp(right) - jobTimestamp(left) || right.id - left.id);
    } else if (jobSort === "score") {
      filtered.sort((left, right) =>
        analysisScore(latestAnalysisByJob.get(right.id)) - analysisScore(latestAnalysisByJob.get(left.id))
        || right.id - left.id,
      );
    } else if (jobSort === "semantic") {
      filtered.sort((left, right) =>
        (semanticSimilarityByJob.get(right.id) ?? -1) - (semanticSimilarityByJob.get(left.id) ?? -1)
        || right.id - left.id,
      );
    } else if (jobSort === "analyzed") {
      filtered.sort((left, right) =>
        analysisTimestamp(latestAnalysisByJob.get(right.id)) - analysisTimestamp(latestAnalysisByJob.get(left.id))
        || right.id - left.id,
      );
    }
    return filtered;
  })();
  const visibleHistory = historyExpanded ? history : history.slice(0, HISTORY_PREVIEW_LIMIT);
  const hiddenLoadedHistory = Math.max(0, history.length - HISTORY_PREVIEW_LIMIT);
  const hasMoreHistory = history.length < effectiveHistoryTotal;


  function navigate(next: HashView) {
    setPageView(next);
    window.history.replaceState(null, "", hashForView(next));
  }

  useEffect(() => {
    const syncView = () => setPageView(viewFromHash(window.location.hash));
    const timer = window.setTimeout(syncView, 0);
    window.addEventListener("hashchange", syncView);
    return () => { window.clearTimeout(timer); window.removeEventListener("hashchange", syncView); };
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try { setSavedJobs(user ? parseBookmarks(localStorage.getItem(bookmarkKey(user.id))) : []); }
      catch { setSavedJobs([]); }
    }, 0);
    return () => window.clearTimeout(timer);
  }, [user]);

  function toggleBookmark(id: number) {
    if (!user) return;
    const next = savedJobs.includes(id) ? savedJobs.filter(item => item !== id) : [...savedJobs, id];
    try { localStorage.setItem(bookmarkKey(user.id), JSON.stringify(next)); setSavedJobs(next); }
    catch { setError("浏览器无法保存收藏，请检查存储权限后重试"); }
  }

  const selectedJob = jobs.find(item => item.id === selectedJobId);
  const latestSelected = latestAnalysisByJob.get(Number(selectedJobId));
  const selectedAnalysisId = latestSelected?.id;
  const selectedAnalysisStatus = latestSelected?.status;
  useEffect(() => {
    if (!token || !selectedResumeId || !selectedJobId || !selectedAnalysisId || busy) return;
    // Keep an explicitly opened historical report; only auto-load an empty selection.
    if (analysis?.resumeId === selectedResumeId && analysis.jobDescriptionId === selectedJobId) return;
    let active = true;
    const session = getAuthSessionId();
    const timer = window.setTimeout(() => {
      setFetchingReport(true);
      void apiRequest<Analysis>(`/api/analysis-histories/${selectedAnalysisId}`).then(item => {
        if (active && isAuthSessionCurrent(session) && item.resumeId === selectedResumeId && item.jobDescriptionId === selectedJobId) setAnalysis(item);
      }).catch(reason => {
        if (active && isAuthSessionCurrent(session)) reportRequestError(reason, "读取报告失败，可刷新数据后重试");
      }).finally(() => { if (active) setFetchingReport(false); });
    }, 0);
    return () => { active = false; window.clearTimeout(timer); };
  }, [token, selectedResumeId, selectedJobId, selectedAnalysisId, selectedAnalysisStatus, analysis?.id, analysis?.status, analysis?.resumeId, analysis?.jobDescriptionId, busy]);

  if (!token) {
    return (
      <main className="auth-shell">
        <section className="auth-story">
          <div className="brand"><span className="brand-mark">R</span><span>ResumeLens</span></div>
          <div className="eyebrow">RAG · RESUME INTELLIGENCE</div>
          <h1>让每一段经历，<br />都对准理想职位。</h1>
          <p>阿里 GTE 本地检索简历证据，{runtimeModeLabel(aiStatus)}。不是关键词打分，而是一条完整的 RAG 链路。</p>
          <div className="auth-metrics">
            <div><strong>768</strong><span>向量维度</span></div>
            <div><strong>Top-K</strong><span>证据召回</span></div>
            <div><strong>MySQL</strong><span>结果持久化</span></div>
          </div>
          <div className="story-orbit story-orbit-one" />
          <div className="story-orbit story-orbit-two" />
        </section>
        <section className="auth-panel">
          <form className="auth-card" onSubmit={submitAuth}>
            <div className="mobile-brand"><span className="brand-mark">R</span> ResumeLens</div>
            <BackendStatus variant="pill" />
            <h2>{authMode === "login" ? "登录工作台" : "创建演示账号"}</h2>
            <p>{authMode === "login" ? "继续你的简历匹配分析" : "30 秒建立自己的分析空间"}</p>
            <label>用户名<input required maxLength={64} value={auth.username} onChange={(e) => setAuth({ ...auth, username: e.target.value })} placeholder="输入用户名" /></label>
            {authMode === "register" && <>
              <label>显示名称<input required maxLength={80} value={auth.displayName} onChange={(e) => setAuth({ ...auth, displayName: e.target.value })} placeholder="例如 Arthur" /></label>
              <label>邮箱<input required type="email" value={auth.email} onChange={(e) => setAuth({ ...auth, email: e.target.value })} placeholder="name@example.com" /></label>
            </>}
            <label>密码<input required type="password" minLength={6} value={auth.password} onChange={(e) => setAuth({ ...auth, password: e.target.value })} placeholder="至少 6 位" /></label>
            {error && <div className="message error">{error}</div>}
            <button className="primary full" disabled={busy === "auth"}>{busy === "auth" ? "正在连接…" : authMode === "login" ? "进入工作台" : "注册并进入"}<ArrowRight size={18} aria-hidden="true" /></button>
            <button className="text-button" type="button" onClick={() => { setAuthMode(authMode === "login" ? "register" : "login"); setError(""); }}>
              {authMode === "login" ? "没有账号？创建一个" : "已有账号？直接登录"}
            </button>
          </form>
        </section>
      </main>
    );
  }

  return (
    <AppShell
      title={navLabel(pageView)}
      subtitle={VIEW_SUBTITLES[pageView]}
      activeView={pageView}
      user={user}
      onLogout={logout}
      onNavigate={navigate}
      actions={<><button className="ghost" type="button" onClick={() => navigate("jobs")}><Plus size={16} aria-hidden="true" />添加岗位</button><button className="ghost" type="button" disabled={!!busy} onClick={() => void loadWorkspace()}>刷新数据</button></>}
    >
        {(error || notice) && <div className={`toast ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>{error ? <CircleAlert size={20} aria-hidden="true" /> : <CircleCheck size={20} aria-hidden="true" />}{error || notice}<button aria-label="关闭通知" onClick={() => { setError(""); setNotice(""); }}><X size={18} aria-hidden="true" /></button></div>}
        <section id="workflow" hidden={pageView !== "match"}>
          <div className="match-context"><label>当前简历<select value={selectedResumeId} disabled={!!busy} onChange={event => chooseResume(event.target.value ? Number(event.target.value) : "")}><option value="">请选择简历</option>{resumes.map(item => <option key={item.id} value={item.id}>{item.title} · {item.candidateName}</option>)}</select></label><button className="text-action" onClick={() => navigate("resumes")}>管理简历</button><div className="match-steps"><span>{selectedResumeId ? <Check size={14} aria-label="已完成" /> : "01"} 选择简历</span><span>{selectedJobId ? <Check size={14} aria-label="已完成" /> : "02"} 选择岗位</span><span className="current">03 核对与改进</span></div></div>
          <div className="match-layout"><aside className="job-rail" aria-label="目标岗位"><div className="entity-library">
                <div className="entity-library-head"><span>目标岗位</span><em>{effectiveJobsTotal}</em></div>
                <div className="job-library-controls">
                  <select aria-label="职位排序" value={jobSort} onChange={(event) => {
                    const next = event.target.value as JobSort;
                    setJobSort(next);
                    if (next === "score" || next === "analyzed") void loadAllJobsForRanking();
                  }} disabled={jobsLoadingMore}>
                    <option value="semantic">语义相关度</option>
                    <option value="recent">最近保存</option>
                    <option value="score">已分析匹配分</option>
                    <option value="analyzed">最近分析</option>
                  </select>
                  <label>
                    <input type="checkbox" checked={jobFilter === "unanalyzed"} disabled={jobsLoadingMore} onChange={(event) => {
                      const next = event.target.checked ? "unanalyzed" : "all";
                      setJobFilter(next);
                      if (next === "unanalyzed") void loadAllJobsForRanking();
                    }} />
                    仅看未分析
                  </label>
                </div>
                <div className="semantic-actions">
                  <select aria-label="Top N 分析数量" value={topMatchCount} onChange={(event) => setTopMatchCount(Number(event.target.value))}>
                    <option value={3}>Top 3</option>
                    <option value={5}>Top 5</option>
                    <option value={10}>Top 10</option>
                  </select>
                  <button className="ghost compact" type="button" disabled={!!busy || !selectedResumeId} onClick={() => void runTopMatchesAnalysis()}>
                    {busy === "top-analysis" ? "逐个精析中…" : "分析向量 Top N"}
                  </button>
                </div>
                <small className="semantic-note">语义相关度用于初筛，综合匹配分来自单独的分析。</small>
                {(jobAnalysesLoading || jobsLoadingMore || jobSemanticStatus === "loading") && <p className="entity-empty">正在整理完整岗位库…</p>}
                {(jobAnalysisDataRequired || jobSemanticDataRequired) && !selectedResumeId && (
                  <p className="entity-empty">请先选择一份简历，再做粗排或按分析结果整理岗位</p>
                )}
                {jobAnalysisDataRequired && jobAnalysesStatus === "error" && (
                  <div className="entity-empty">
                    匹配信息加载失败：{jobAnalysesError || "请稍后重试"}
                    {selectedResumeId && (
                      <button className="ghost compact" type="button" onClick={() => void loadJobAnalyses(Number(selectedResumeId))}>
                        重试
                      </button>
                    )}
                  </div>
                )}
                {jobSemanticDataRequired && jobSemanticStatus === "error" && (
                  <div className="entity-empty">
                    向量粗排失败：{jobSemanticError || "请稍后重试"}
                    {selectedResumeId && (
                      <button className="ghost compact" type="button" onClick={() => void loadSemanticMatches(Number(selectedResumeId))}>
                        重试
                      </button>
                    )}
                  </div>
                )}
                {jobs.length === 0 ? (
                  <p className="entity-empty">暂无职位，保存后会出现在这里</p>
                ) : (jobAnalysisDataRequired && !jobAnalysisDataReady)
                    || (jobSemanticDataRequired && !jobSemanticDataReady) ? null : visibleJobs.length === 0 ? (
                  <p className="entity-empty">当前简历下没有未分析职位</p>
                ) : visibleJobs.map((item) => {
                  const latest = latestAnalysisByJob.get(item.id);
                  const semanticSimilarity = semanticSimilarityByJob.get(item.id);
                  return (
                    <div className={`entity-row ${selectedJobId === item.id ? "selected" : ""} ${editingJobId === item.id ? "editing" : ""}`} key={item.id}>
                      <button type="button" className="entity-main job-entity-main" aria-pressed={selectedJobId === item.id} disabled={!!busy} onClick={() => chooseJob(item.id)} title="选为匹配职位">
                        <span className="job-entity-copy">
                          <strong>{item.title}</strong>
                          <small>{item.companyName}{item.location ? ` · ${item.location}` : ""}</small>
                        </span>
                        <em className={`job-score ${jobSort === "semantic" ? "semantic" : latest?.status.toLowerCase() || "unanalyzed"}`}>
                          {jobSort === "semantic" && typeof semanticSimilarity === "number"
                            ? `语义相关度 ${semanticSimilarity.toFixed(2)}`
                            : jobScoreLabel(latest)}
                        </em>
                      </button>
                      <div className="entity-actions">
                        <button type="button" className="ghost compact" aria-pressed={savedJobs.includes(item.id)} onClick={() => toggleBookmark(item.id)}>{savedJobs.includes(item.id) ? "已保存" : "保存"}</button><Link className="ghost compact" href={`/jobs/${item.id}`}>详情</Link>
                        <button type="button" className="ghost compact" onClick={() => beginEditJob(item)} disabled={!!busy}>编辑</button>
                        <button type="button" className="ghost compact danger" onClick={() => void deleteJob(item.id)} disabled={!!busy}>删除</button>
                      </div>
                    </div>
                  );
                })}
                {jobs.length < effectiveJobsTotal && (
                  <div className="entity-load-more">
                    <button className="ghost compact" type="button" disabled={jobsLoadingMore} onClick={() => void loadMoreJobs()}>
                      {jobsLoadingMore ? "加载中…" : `继续加载（还有 ${effectiveJobsTotal - jobs.length} 个）`}
                    </button>
                  </div>
                )}
              </div></aside>
            <section className="match-report" id="result" aria-label="岗位匹配报告" aria-busy={busy === "analysis" || fetchingReport || analysis?.status === "PENDING"}>
              {analysis?.status === "COMPLETED" && busy !== "analysis" ? <MatchReport key={analysis.id} analysis={analysis} job={jobs.find(item => item.id === analysis.jobDescriptionId)} saved={savedJobs.includes(analysis.jobDescriptionId)} disabled={!!busy} onBookmark={() => toggleBookmark(analysis.jobDescriptionId)} onExportMarkdown={exportMarkdown} onExportPdf={exportPdf} onSaveCopy={copy => { setResumes(items => [copy, ...items.filter(item => item.id !== copy.id)]); chooseResume(copy.id); invalidateSemanticMatches(); setNotice("修改版已另存为新简历，原简历未覆盖；可重新分析对比。"); }} /> : <div className="report-state" role="status">
                <span className="report-state-mark" aria-hidden="true">{busy === "analysis" || analysis?.status === "PENDING" ? <LoaderCircle className="spin" size={24} /> : analysis?.status === "FAILED" ? <CircleAlert size={24} /> : <ArrowUpRight size={24} />}</span>
                <span className="eyebrow">MATCH REVIEW</span><h2>{fetchingReport ? "正在读取匹配报告…" : busy === "analysis" || analysis?.status === "PENDING" ? "正在逐项核对岗位要求" : analysis?.status === "FAILED" ? "这次分析没有完成" : selectedJob ? selectedJob.title : "先选一个目标岗位"}</h2>
                <p>{fetchingReport ? "正在读取当前简历与岗位对应的分析。" : busy === "analysis" || analysis?.status === "PENDING" ? pendingAnalysisLabel(aiStatus) : analysis?.status === "FAILED" ? analysis.summary || "输入仍然保留，可以重试分析。" : selectedJob ? `${selectedJob.companyName}${selectedJob.location ? ` · ${selectedJob.location}` : ""} · ${selectedResumeId ? "准备好后，生成你的匹配报告。" : "先添加或选择一份简历。"}` : "保存简历和岗位后，这里会显示真实的匹配依据与改进建议。"}</p>
                {(busy === "analysis" || analysis?.status === "PENDING" || fetchingReport) ? <><progress aria-label="匹配处理中" /><button className="ghost" disabled={!!busy} onClick={() => void loadWorkspace()}>刷新报告状态</button></> : <button className="primary" type="button" disabled={!!busy || !selectedResumeId || !selectedJobId} onClick={() => void runAnalysis()}>{analysis?.status === "FAILED" ? "重试分析" : <>开始匹配分析<ArrowRight size={16} aria-hidden="true" /></>}</button>}
                {!resumes.length && <button className="text-action" onClick={() => navigate("resumes")}>添加第一份简历</button>}
                <small>{runtimeModeLabel(aiStatus)}</small>
              </div>}
              {analysis?.status === "COMPLETED" && <div className="report-rerun"><small>{runtimeModeLabel(aiStatus)}</small><button className="text-action" disabled={!!busy || !selectedResumeId || !selectedJobId || analysis.resumeId !== selectedResumeId || analysis.jobDescriptionId !== selectedJobId} onClick={() => void runAnalysis()}>重新分析当前组合</button></div>}
            </section>
          </div>
        </section>
        <section className="editor-view" id="resumes" hidden={pageView !== "resumes"}><div className="editor-toolbar"><p>上传文件或直接粘贴正文，保存后选择目标岗位。</p><button className="ghost" onClick={() => { resetResumeEditor(); setNotice(""); }}>新建空白简历</button><button className="ghost" onClick={fillSampleForms}>填入示例</button><button className="ghost" onClick={() => navigate("match")}>返回匹配</button></div>            <form className="step-card" onSubmit={saveResume}>
              <div className="step-title">
                <span>01</span>
                <div>
                  <h3>{editingResumeId ? `编辑简历 #${editingResumeId}` : "添加简历"}</h3>
                  <p>{editingResumeId ? "修改正文后保存，重新匹配时将使用新内容" : "上传文件或粘贴原文"}</p>
                </div>
              </div>
              <label className={`file-drop ${editingResumeId ? "disabled-drop" : ""}`}>
                <input type="file" accept=".pdf,.doc,.docx,.txt,.md" onChange={chooseFile} disabled={!!editingResumeId} />
                <span className="upload-icon">↑</span>
                <strong>{editingResumeId ? "编辑模式仅支持更新文本" : file ? file.name : "选择简历文件（可选）"}</strong>
                <small>PDF · DOCX · TXT，最大 20MB</small>
              </label>
              <div className="field-row"><label>简历标题<input required value={resumeForm.title} onChange={(e) => setResumeForm({ ...resumeForm, title: e.target.value })} placeholder="Java 后端开发简历" /></label><label>候选人<input required value={resumeForm.candidateName} onChange={(e) => setResumeForm({ ...resumeForm, candidateName: e.target.value })} placeholder="姓名" /></label></div>
              <div className="field-row"><label>手机<input value={resumeForm.phone} onChange={(e) => setResumeForm({ ...resumeForm, phone: e.target.value })} placeholder="可选" /></label><label>邮箱<input type="email" value={resumeForm.email} onChange={(e) => setResumeForm({ ...resumeForm, email: e.target.value })} placeholder="可选" /></label></div>
              <label>简历文本<textarea required={!file || !!editingResumeId} rows={8} value={resumeForm.rawText} onChange={(e) => setResumeForm({ ...resumeForm, rawText: e.target.value })} disabled={!!file && !editingResumeId} placeholder={file ? "已选择文件，将由服务端解析正文；保存后可在详情中编辑" : "可直接粘贴简历正文；上传文件时由服务端解析"} /></label>
              <div className="form-actions">
                <button className="secondary" disabled={!!busy}>{busy === "resume" ? file ? "上传与解析中…" : "保存中…" : editingResumeId ? "更新简历" : "保存简历"}</button>
                {editingResumeId && (
                  <button className="ghost" type="button" onClick={resetResumeEditor}>取消编辑</button>
                )}
              </div>
              <div className="entity-library">
                <div className="entity-library-head"><span>已存简历</span><em>{resumes.length}</em></div>
                {resumes.length === 0 ? (
                  <p className="entity-empty">暂无简历，保存后会出现在这里</p>
                ) : resumes.map((item) => (
                  <div className={`entity-row ${selectedResumeId === item.id ? "selected" : ""} ${editingResumeId === item.id ? "editing" : ""}`} key={item.id}>
                    <button type="button" className="entity-main" onClick={() => chooseResume(item.id)} title="选为匹配简历">
                      <strong>#{item.id} · {item.title}</strong>
                      <small>{item.candidateName}{item.originalFileName ? ` · ${item.originalFileName}` : ""}</small>
                    </button>
                    <div className="entity-actions">
                      <Link className="ghost compact" href={`/resumes/${item.id}`}>详情</Link>
                      <button type="button" className="ghost compact" onClick={() => void beginEditResume(item)} disabled={!!busy}>编辑</button>
                      <button type="button" className="ghost compact danger" onClick={() => void deleteResume(item.id)} disabled={!!busy}>删除</button>
                    </div>
                  </div>
                ))}
              </div>
            </form></section>
        <section className="editor-view" id="jobs" hidden={pageView !== "jobs"}><div className="editor-toolbar"><p>填写目标岗位，也支持批量导入。</p><button className="ghost" onClick={resetJobEditor}>新建空白岗位</button><button className="ghost" onClick={fillSampleForms}>填入示例</button><button className="ghost" onClick={() => navigate("match")}>返回匹配</button></div>            <form className="step-card" onSubmit={saveJob}>
              <div className="step-title">
                <span>02</span>
                <div>
                  <h3>{editingJobId ? `编辑职位 #${editingJobId}` : "录入职位 JD"}</h3>
                  <p>{editingJobId ? "修改岗位要求后保存" : "告诉 AI 目标岗位要求"}</p>
                </div>
              </div>
              <div className="field-row"><label>职位名称<input required value={jobForm.title} onChange={(e) => setJobForm({ ...jobForm, title: e.target.value })} placeholder="Java RAG 工程师" /></label><label>公司名称<input required value={jobForm.companyName} onChange={(e) => setJobForm({ ...jobForm, companyName: e.target.value })} placeholder="公司" /></label></div>
              <div className="field-row"><label>工作地点<input value={jobForm.location} onChange={(e) => setJobForm({ ...jobForm, location: e.target.value })} placeholder="杭州" /></label><label>用工类型<input value={jobForm.employmentType} onChange={(e) => setJobForm({ ...jobForm, employmentType: e.target.value })} /></label></div>
              <label>岗位描述<textarea required rows={4} value={jobForm.description} onChange={(e) => setJobForm({ ...jobForm, description: e.target.value })} placeholder="岗位职责、业务方向…" /></label>
              <label>任职要求<textarea rows={4} value={jobForm.requirements} onChange={(e) => setJobForm({ ...jobForm, requirements: e.target.value })} placeholder="技术栈、经验要求…" /></label>
              <div className="form-actions">
                <button className="secondary" disabled={!!busy}>{busy === "job" ? "保存中…" : editingJobId ? "更新职位" : "保存职位"}</button>
                {editingJobId && (
                  <button className="ghost" type="button" onClick={resetJobEditor}>取消编辑</button>
                )}
                <button className="ghost" type="button" onClick={() => setShowBulkImport((v) => !v)} disabled={!!busy}>
                  {showBulkImport ? "收起批量导入" : "批量导入"}
                </button>
              </div>
              {showBulkImport && (
                <div className="bulk-import-panel">
                  <div className="bulk-import-head">
                    <strong>批量导入 JD</strong>
                    <small>支持 JSON 数组 / {"{ items }"} / NDJSON</small>
                  </div>
                  <textarea
                    rows={8}
                    value={bulkImportText}
                    onChange={(e) => setBulkImportText(e.target.value)}
                    placeholder='[{"title":"...","companyName":"...","description":"..."}]'
                  />
                  <div className="form-actions">
                    <button className="secondary" type="button" disabled={!!busy} onClick={() => void bulkImportJobs()}>
                      {busy === "bulk-import" ? "导入中…" : "确认导入"}
                    </button>
                    <button className="ghost" type="button" onClick={() => setBulkImportText(JSON.stringify(SAMPLE_BULK_JOBS, null, 2))}>
                      填入示例 JSON
                    </button>
                  </div>
                </div>
              )}
            </form><details className="sample-action"><summary>用示例数据体验</summary><p>会在当前账号中保存示例简历和岗位，并提交一次分析。</p><button className="ghost" disabled={!!busy} onClick={() => { navigate("match"); void saveSampleAndAnalyze(); }}>保存示例并分析</button></details></section>
        <section id="saved" className="saved-view" hidden={pageView !== "saved"}>{savedJobs.length === 0 ? <div className="empty-workspace"><h2>还没有保存的岗位</h2><p>在岗位或报告旁点「保存」，就能在这里继续准备。</p><button className="primary" onClick={() => navigate("match")}>去看目标岗位<ArrowRight size={16} aria-hidden="true" /></button></div> : <><div className="saved-grid">{jobs.filter(item => savedJobs.includes(item.id)).map(item => <article className="saved-job" key={item.id}><p>{item.companyName} · {item.location || "地点未填写"}</p><h3>{item.title}</h3><div className="form-actions"><button className="primary" onClick={() => { chooseJob(item.id); navigate("match"); }}>查看匹配</button><Link className="ghost" href={`/jobs/${item.id}`}>岗位详情</Link><button className="text-action" onClick={() => toggleBookmark(item.id)}>取消保存</button></div></article>)}</div>{jobs.length < effectiveJobsTotal && <button className="ghost" disabled={jobsLoadingMore} onClick={() => void loadAllJobsForRanking()}>加载其余岗位中的收藏</button>}<p className="review-caveat">仅展示仍可访问的岗位。收藏范围：当前浏览器、当前账号。</p></>}</section>
                <section className="history" id="history" hidden={pageView !== "history"}>
          <p className="history-count">{effectiveHistoryTotal} 条记录</p>
          <div className="history-table">
            <div className="history-row history-head"><span>报告</span><span>岗位</span><span>状态</span><span>匹配度</span><span>时间</span></div>
            {history.length === 0 ? (
              <div className="history-empty">
                暂无记录。选择简历和岗位，完成第一次分析后会显示在这里。
              </div>
            ) : visibleHistory.map((item) => (
              <button className="history-row" key={item.id} type="button" onClick={() => { activeAnalysisRun.current = null; chooseResume(item.resumeId); chooseJob(item.jobDescriptionId); setAnalysis(item); navigate("match"); document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }); }}>
                <span>#{item.id} · {item.resumeTitle}</span>
                <span>{item.jobTitle}</span>
                <span><i className={item.status.toLowerCase()} />{item.status}</span>
                <span>{item.status !== "COMPLETED" || item.matchScore == null ? <strong>{item.status === "FAILED" ? "失败" : "分析中"}</strong> : <><strong>{Number(item.matchScore).toFixed(0)}</strong> / 100</>}</span>
                <span>{new Date(item.createdAt).toLocaleDateString("zh-CN")}</span>
              </button>
            ))}
            {history.length > HISTORY_PREVIEW_LIMIT && (
              <div className="history-more">
                <button className="ghost compact" type="button" onClick={() => setHistoryExpanded((expanded) => !expanded)}>
                  {historyExpanded ? "收起" : `展开其余 ${hiddenLoadedHistory} 条`}
                </button>
                {historyExpanded && hasMoreHistory && (
                  <button className="ghost compact" type="button" disabled={historyLoadingMore} onClick={() => void loadMoreHistory()}>
                    {historyLoadingMore ? "加载中…" : `继续加载（还有 ${effectiveHistoryTotal - history.length} 条）`}
                  </button>
                )}
              </div>
            )}
          </div>
        </section>
    </AppShell>
  );
}
