import { useCallback, useEffect, useRef, useState } from "react";
import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import {
  analysisPollTimeoutMs,
  mergeLatestAnalysisSummaries,
  pollAnalysisUntilSettled,
} from "../analysis-poll";
import { createHistoryOpener } from "../history-open";
import {
  apiRequest,
  getAuthSessionId,
  isAuthSessionCurrent,
  visibleApiErrorMessage,
  type AiStatus,
  type Analysis,
  type AnalysisSummary,
  type Job,
  type JobSemanticMatch,
  type Resume,
} from "../lib/api";
import {
  buildReportMarkdown,
  buildReportPrintHtml,
  downloadTextFile,
  openPrintableReport,
  reportFilename,
} from "../report-export";
import { generationProgressLabel } from "../runtime-mode";
import { saveSampleData, submitAnalysis } from "../sample-data";
import { semanticAnalysisTargets } from "../semantic-ranking";
import { EMPTY_PASTE_JOB, pasteJobPayload, type JobSource, type PasteJobForm } from "../workbench";
import type { HashView } from "../workspace-nav";
import type { AnalysisRun, JobAnalysesStatus, JobSort } from "./workspace-helpers";

type UseAnalysisRunnerParams = {
  token: string;
  busy: string;
  aiStatus: AiStatus | null;
  analysis: Analysis | null;
  setAnalysis: Dispatch<SetStateAction<Analysis | null>>;
  setFetchingReport: Dispatch<SetStateAction<boolean>>;
  activeAnalysisRunRef: MutableRefObject<AnalysisRun | null>;
  selectedResumeId: number | "";
  selectedJobId: number | "";
  selectedAnalysisId?: number;
  selectedAnalysisStatus?: AnalysisSummary["status"];
  effectiveJobSource: JobSource;
  pasteJob: PasteJobForm;
  jobSemanticDataReady: boolean;
  jobSemanticMatches: JobSemanticMatch[];
  jobAnalysisDataReady: boolean;
  jobLatestAnalyses: AnalysisSummary[];
  topMatchCount: number;
  setResumes: Dispatch<SetStateAction<Resume[]>>;
  setJobs: Dispatch<SetStateAction<Job[]>>;
  setJobsTotal: Dispatch<SetStateAction<number>>;
  setHistory: Dispatch<SetStateAction<Analysis[]>>;
  setJobLatestAnalyses: Dispatch<SetStateAction<AnalysisSummary[]>>;
  setJobAnalysesResumeId: Dispatch<SetStateAction<number | null>>;
  setJobAnalysesStatus: Dispatch<SetStateAction<JobAnalysesStatus>>;
  setJobSort: Dispatch<SetStateAction<JobSort>>;
  loadJobAnalyses: (resumeId: number) => Promise<void>;
  loadSemanticMatches: (resumeId: number) => Promise<JobSemanticMatch[] | null>;
  invalidateSemanticMatches: () => void;
  chooseResume: (next: number | "") => void;
  chooseJob: (next: number | "") => void;
  setJobSource: Dispatch<SetStateAction<JobSource>>;
  setPasteJob: Dispatch<SetStateAction<PasteJobForm>>;
  setMatchMode: Dispatch<SetStateAction<"compose" | "report">>;
  navigate: (next: HashView) => void;
  applySampleDrafts: () => void;
  setBusy: Dispatch<SetStateAction<string>>;
  setError: Dispatch<SetStateAction<string>>;
  setNotice: Dispatch<SetStateAction<string>>;
  reportRequestError: (reason: unknown, fallback: string) => void;
};

export function useAnalysisRunner({
  token,
  busy,
  aiStatus,
  analysis,
  setAnalysis,
  setFetchingReport,
  activeAnalysisRunRef,
  selectedResumeId,
  selectedJobId,
  selectedAnalysisId,
  selectedAnalysisStatus,
  effectiveJobSource,
  pasteJob,
  jobSemanticDataReady,
  jobSemanticMatches,
  jobAnalysisDataReady,
  jobLatestAnalyses,
  topMatchCount,
  setResumes,
  setJobs,
  setJobsTotal,
  setHistory,
  setJobLatestAnalyses,
  setJobAnalysesResumeId,
  setJobAnalysesStatus,
  setJobSort,
  loadJobAnalyses,
  loadSemanticMatches,
  invalidateSemanticMatches,
  chooseResume,
  chooseJob,
  setJobSource,
  setPasteJob,
  setMatchMode,
  navigate,
  applySampleDrafts,
  setBusy,
  setError,
  setNotice,
  reportRequestError,
}: UseAnalysisRunnerParams) {
  const [reportFetchError, setReportFetchError] = useState<{ analysisId: number; message: string } | null>(null);
  const [reportRetry, setReportRetry] = useState(0);

  const analysisRunSequence = useRef(0);
  // List rows carry no JD snapshot; opening one renders the detail fetched by id (see history-open.ts).
  const [openHistory] = useState(() => createHistoryOpener<Analysis>());

  const clearReportFetchError = useCallback(() => {
    setReportFetchError(null);
  }, []);

  useEffect(() => {
    if (!token || !selectedResumeId || !selectedJobId || !selectedAnalysisId || busy) return;
    // Keep an explicitly opened historical report; only auto-load an empty selection.
    if (analysis?.resumeId === selectedResumeId && analysis.jobDescriptionId === selectedJobId) return;
    let active = true;
    const session = getAuthSessionId();
    const timer = window.setTimeout(() => {
      setFetchingReport(true);
      void apiRequest<Analysis>(`/api/analysis-histories/${selectedAnalysisId}`).then((item) => {
        if (!active || !isAuthSessionCurrent(session)) return;
        setReportFetchError(null);
        if (item.resumeId === selectedResumeId && item.jobDescriptionId === selectedJobId) setAnalysis(item);
      }).catch((reason) => {
        if (!active || !isAuthSessionCurrent(session)) return;
        const message = visibleApiErrorMessage(reason, "读取报告失败");
        if (message != null) setReportFetchError({ analysisId: selectedAnalysisId, message });
      }).finally(() => { if (active) setFetchingReport(false); });
    }, 0);
    return () => { active = false; window.clearTimeout(timer); };
  }, [
    token,
    selectedResumeId,
    selectedJobId,
    selectedAnalysisId,
    selectedAnalysisStatus,
    analysis?.id,
    analysis?.status,
    analysis?.resumeId,
    analysis?.jobDescriptionId,
    busy,
    reportRetry,
    setAnalysis,
    setFetchingReport,
  ]);

  function beginAnalysisRun(resumeId: number, jobId: number) {
    const run = { generation: ++analysisRunSequence.current, resumeId, jobId };
    activeAnalysisRunRef.current = run;
    return run;
  }

  function isCurrentAnalysisRun(run: AnalysisRun) {
    return activeAnalysisRunRef.current?.generation === run.generation;
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
    activeAnalysisRunRef.current = null;
    if (timedOut) {
      setNotice("分析仍在后台运行，可稍后在历史记录中查看结果");
      return current;
    }
    if (current.status === "FAILED") throw new Error(current.summary || "AI 分析失败");
    return current;
  }

  /**
   * Starts an analysis of the given pair. The ids are passed in rather than read from state, because the
   * workbench calls this right after saving a pasted job, before the new selection has rendered.
   */
  async function runAnalysis(resumeId: number | "" = selectedResumeId, jobId: number | "" = selectedJobId) {
    if (!resumeId || !jobId) {
      setError("请先选择简历和职位 JD，或点「一键示例匹配」");
      return;
    }
    setMatchMode("report");
    setBusy("analysis");
    setError("");
    setNotice(`正在检索（语义阈值门控 + 关键词重排），${generationProgressLabel(aiStatus)}`);
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
        activeAnalysisRunRef.current = null;
        reportRequestError(reason, "AI 分析失败");
      }
    } finally {
      setBusy("");
    }
  }

  /** The workbench button: a pasted job is saved to the job library first, then the pair is analysed. */
  async function startFromWorkbench() {
    if (!selectedResumeId) {
      setError("请先选择一份简历");
      return;
    }
    const resumeId = selectedResumeId;
    if (effectiveJobSource === "library") {
      await runAnalysis(resumeId, selectedJobId);
      return;
    }
    const checked = pasteJobPayload(pasteJob);
    if (!checked.ok) {
      setError(checked.problems.join("；"));
      return;
    }
    const session = getAuthSessionId();
    setBusy("paste-job");
    setError("");
    let saved: Job;
    try {
      saved = await apiRequest<Job>("/api/job-descriptions", { method: "POST", body: JSON.stringify(checked.payload) });
    } catch (reason) {
      if (isAuthSessionCurrent(session)) {
        reportRequestError(reason, "职位保存失败，粘贴的内容仍保留");
        setBusy("");
      }
      return;
    }
    if (!isAuthSessionCurrent(session)) return;
    setJobs((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
    setJobsTotal((total) => total + 1);
    invalidateSemanticMatches();
    chooseJob(saved.id);
    setPasteJob({ ...EMPTY_PASTE_JOB });
    setJobSource("library");
    setBusy("");
    await runAnalysis(resumeId, saved.id);
  }

  async function saveSampleAndAnalyze() {
    setBusy("sample");
    setError("");
    let run: AnalysisRun | null = null;
    try {
      applySampleDrafts();
      const { resume: createdResume, job: createdJob } = await saveSampleData(apiRequest);
      setResumes((items) => [createdResume, ...items.filter((item) => item.id !== createdResume.id)]);
      setJobs((items) => [createdJob, ...items.filter((item) => item.id !== createdJob.id)]);
      setJobsTotal((total) => total + 1);
      chooseResume(createdResume.id);
      chooseJob(createdJob.id);
      setNotice("示例数据已保存，正在启动 RAG 匹配分析…");
      setMatchMode("report");
      setBusy("analysis");
      run = beginAnalysisRun(createdResume.id, createdJob.id);
      const result = await submitAnalysis(apiRequest, createdResume.id, createdJob.id);
      const completed = await waitForAnalysis(result, run);
      if (!completed) return;
      setNotice("示例匹配完成，下方报告已更新");
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (!run || isCurrentAnalysisRun(run)) {
        activeAnalysisRunRef.current = null;
        reportRequestError(reason, "示例流程失败");
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
        activeAnalysisRunRef.current = null;
        reportRequestError(reason, "Top N 分析失败");
      }
    } finally {
      setBusy("");
    }
  }

  function openHistoryReport(id: number) {
    const session = getAuthSessionId();
    return openHistory(id, {
      fetchById: (reportId) => apiRequest<Analysis>(`/api/analysis-histories/${reportId}`),
      isSessionCurrent: () => isAuthSessionCurrent(session),
      show: (report) => {
        activeAnalysisRunRef.current = null;
        chooseResume(report.resumeId);
        chooseJob(report.jobDescriptionId);
        setAnalysis(report);
        setMatchMode("report");
        navigate("match");
      },
      fail: (reason) => reportRequestError(reason, "读取报告失败"),
    });
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

  return {
    reportFetchError,
    retryReportFetch: () => setReportRetry((value) => value + 1),
    clearReportFetchError,
    runAnalysis,
    startFromWorkbench,
    saveSampleAndAnalyze,
    runTopMatchesAnalysis,
    openHistoryReport,
    exportMarkdown,
    exportPdf,
  };
}
