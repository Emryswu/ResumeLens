"use client";

import { CircleAlert, CircleCheck, Plus, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { AppShell } from "../components/AppShell";
import { LoginPage, type AuthForm, type AuthMode } from "../components/LoginPage";
import { ErrorState } from "../components/StateBlock";
import { Workbench } from "../components/Workbench";
import { HistorySection } from "../components/workspace/HistorySection";
import { JobLibrarySection } from "../components/workspace/JobLibrarySection";
import { MatchReportSection } from "../components/workspace/MatchReportSection";
import { ResumeLibrarySection } from "../components/workspace/ResumeLibrarySection";
import { SavedJobsSection } from "../components/workspace/SavedJobsSection";
import {
  AUTH_EXPIRED_EVENT,
  type AiStatus,
  type Analysis,
  type Job,
  type PageData,
  type Resume,
  type User,
  apiRequest,
  getAuthSessionId,
  isAuthSessionCurrent,
  logoutSession,
  refreshSession,
  setAccessToken,
  visibleApiErrorMessage,
} from "../lib/api";
import { runtimeModeLabel } from "../runtime-mode";
import { signInRequest } from "../sign-in";
import { reportPanelState, type LoadState } from "../view-states";
import { EMPTY_PASTE_JOB, workbenchAction, type JobSource, type PasteJobForm } from "../workbench";
import { hashForView, navLabel, viewFromHash, type HashView } from "../workspace-nav";
import { useAnalysisRunner } from "./use-analysis-runner";
import { useHistoryLibrary } from "./use-history-library";
import { useJobRanking } from "./use-job-ranking";
import { useLibraryEditors } from "./use-library-editors";
import { useSavedJobs } from "./use-saved-jobs";
import {
  HISTORY_PAGE_SIZE,
  JOB_PAGE_SIZE,
  VIEW_SUBTITLES,
  type AnalysisRun,
} from "./workspace-helpers";

export default function Home() {
  const [token, setToken] = useState("");
  const [pageView, setPageView] = useState<HashView>("match");
  const [fetchingReport, setFetchingReport] = useState(false);
  // Bumped by every chooseResume call. Re-selecting the same resume leaves
  // selectedResumeId unchanged, so without this counter nothing would depend-change
  // to refetch the per-job scores that chooseResume just cleared.
  const [resumeScopeEpoch, setResumeScopeEpoch] = useState(0);
  const [user, setUser] = useState<User | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [auth, setAuth] = useState<AuthForm>({ username: "", password: "", email: "", displayName: "" });
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [jobsTotal, setJobsTotal] = useState(0);
  const [jobsPage, setJobsPage] = useState(0);
  const [selectedResumeId, setSelectedResumeId] = useState<number | "">("");
  const [selectedJobId, setSelectedJobId] = useState<number | "">("");
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  // "N 天前" only needs day precision, so the page reads the clock once instead of during every render.
  const [pageOpenedAt] = useState(() => Date.now());
  const [aiStatus, setAiStatus] = useState<AiStatus | null>(null);
  // The match page is the workbench (pick a resume and a job) until a report is opened or an analysis starts.
  const [matchMode, setMatchMode] = useState<"compose" | "report">("compose");
  const [jobSource, setJobSource] = useState<JobSource>("library");
  const [pasteJob, setPasteJob] = useState<PasteJobForm>({ ...EMPTY_PASTE_JOB });
  // Until the first load succeeds the lists are unknown, not empty: show placeholders, then data or a retry.
  const [workspaceStatus, setWorkspaceStatus] = useState<LoadState>("loading");
  const [workspaceError, setWorkspaceError] = useState("");
  const workspaceLoaded = useRef(false);
  // An empty job library has nothing to pick from, so the workbench opens on the paste tab instead.
  const effectiveJobSource: JobSource = jobSource === "library" && workspaceStatus === "ready" && jobs.length === 0 ? "paste" : jobSource;
  const fileInputRef = useRef<HTMLInputElement>(null);
  const activeAnalysisRun = useRef<AnalysisRun | null>(null);

  const reportRequestError = useCallback((reason: unknown, fallback: string) => {
    const message = visibleApiErrorMessage(reason, fallback);
    if (message == null) return;
    setError(message);
  }, []);

  const { savedJobs, setSavedJobs, toggleBookmark } = useSavedJobs(user, setError);

  const historyLib = useHistoryLibrary({
    savedJobs,
    pageOpenedAt,
    setError,
    reportRequestError,
  });
  const { applyInitialHistory, resetHistory } = historyLib;

  const ranking = useJobRanking({
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
  });

  const { resetForResumeChange, resetAllRanking, invalidateSemanticMatches } = ranking;

  const chooseResume = useCallback((next: number | "") => {
    setFetchingReport(false);
    const run = activeAnalysisRun.current;
    if (run && run.resumeId !== next) activeAnalysisRun.current = null;
    resetForResumeChange(next);
    setAnalysis((current) => (current && current.resumeId !== next ? null : current));
    setResumeScopeEpoch((value) => value + 1);
    setSelectedResumeId(next);
  }, [resetForResumeChange]);

  const chooseJob = useCallback((next: number | "") => {
    setFetchingReport(false);
    const run = activeAnalysisRun.current;
    if (run && run.jobId !== next) activeAnalysisRun.current = null;
    setAnalysis((current) => (current && current.jobDescriptionId !== next ? null : current));
    setSelectedJobId(next);
  }, []);

  const navigate = useCallback((next: HashView) => {
    setPageView(next);
    window.history.replaceState(null, "", hashForView(next));
  }, []);

  const editors = useLibraryEditors({
    user,
    selectedResumeId,
    selectedJobId,
    analysis,
    setAnalysis,
    setResumes,
    setJobs,
    setJobsTotal,
    chooseResume,
    chooseJob,
    invalidateSemanticMatches,
    setJobSource,
    setMatchMode,
    navigate,
    setBusy,
    setError,
    setNotice,
    reportRequestError,
  });

  const selectedJob = jobs.find((item) => item.id === selectedJobId);
  const latestSelected = ranking.latestAnalysisByJob.get(Number(selectedJobId));
  const selectedAnalysisId = latestSelected?.id;
  const selectedAnalysisStatus = latestSelected?.status;

  const runner = useAnalysisRunner({
    token,
    busy,
    aiStatus,
    analysis,
    setAnalysis,
    setFetchingReport,
    activeAnalysisRunRef: activeAnalysisRun,
    selectedResumeId,
    selectedJobId,
    selectedAnalysisId,
    selectedAnalysisStatus,
    effectiveJobSource,
    pasteJob,
    jobSemanticDataReady: ranking.jobSemanticDataReady,
    jobSemanticMatches: ranking.jobSemanticMatches,
    jobAnalysisDataReady: ranking.jobAnalysisDataReady,
    jobLatestAnalyses: ranking.jobLatestAnalyses,
    topMatchCount: ranking.topMatchCount,
    setResumes,
    setJobs,
    setJobsTotal,
    setHistory: historyLib.setHistory,
    setJobLatestAnalyses: ranking.setJobLatestAnalyses,
    setJobAnalysesResumeId: ranking.setJobAnalysesResumeId,
    setJobAnalysesStatus: ranking.setJobAnalysesStatus,
    setJobSort: ranking.setJobSort,
    loadJobAnalyses: ranking.loadJobAnalyses,
    loadSemanticMatches: ranking.loadSemanticMatches,
    invalidateSemanticMatches,
    chooseResume,
    chooseJob,
    setJobSource,
    setPasteJob,
    setMatchMode,
    navigate,
    applySampleDrafts: editors.applySampleDrafts,
    setBusy,
    setError,
    setNotice,
    reportRequestError,
  });

  const { resetAllEditors } = editors;
  const { clearReportFetchError } = runner;

  function clearAuthenticatedView() {
    activeAnalysisRun.current = null;
    resetAllRanking();
    setToken("");
    setUser(null);
    setAiStatus(null);
    setSavedJobs([]);
    setFetchingReport(false);
    setAnalysis(null);
    setResumes([]);
    setJobs([]);
    setJobsTotal(0);
    setJobsPage(0);
    resetHistory();
    setSelectedResumeId("");
    setSelectedJobId("");
    setAuth({ username: "", password: "", email: "", displayName: "" });
    resetAllEditors();
    setMatchMode("compose");
    setJobSource("library");
    setPasteJob({ ...EMPTY_PASTE_JOB });
    setNotice("");
    setBusy("");
    workspaceLoaded.current = false;
    setWorkspaceStatus("loading");
    setWorkspaceError("");
    clearReportFetchError();
  }

  const loadWorkspace = useCallback(async () => {
    const sessionId = getAuthSessionId();
    setBusy("loading");
    setError("");
    if (!workspaceLoaded.current) {
      setWorkspaceStatus("loading");
      setWorkspaceError("");
    }
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
      invalidateSemanticMatches();
      applyInitialHistory(historyPage);
      workspaceLoaded.current = true;
      setWorkspaceStatus("ready");
      if (!selectedResumeId && resumePage.content[0]) chooseResume(resumePage.content[0].id);
      if (!selectedJobId && jobPage.content[0]) chooseJob(jobPage.content[0].id);
      if (analysis) {
        const refreshed = historyPage.content.find((item) => item.id === analysis.id);
        if (refreshed) setAnalysis(refreshed);
      }
    } catch (reason) {
      if (!isAuthSessionCurrent(sessionId)) return;
      const message = visibleApiErrorMessage(reason, "无法连接后端");
      if (message == null) return;
      // A failed refresh keeps what is on screen; a failed first load has nothing to show but the retry.
      if (workspaceLoaded.current) setError(message);
      else {
        setWorkspaceError(message);
        setWorkspaceStatus("error");
      }
    } finally {
      if (isAuthSessionCurrent(sessionId)) setBusy("");
    }
  }, [analysis, applyInitialHistory, chooseJob, chooseResume, invalidateSemanticMatches, selectedJobId, selectedResumeId]);

  async function loadAiStatus(sessionId: number, isActive: () => boolean = () => true) {
    try {
      const status = await apiRequest<AiStatus>("/api/ai/status");
      if (isActive() && isAuthSessionCurrent(sessionId)) setAiStatus(status);
    } catch {
      // Keep the UI neutral when runtime mode cannot be confirmed.
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
    void refreshSession().then(async (session) => {
      if (!session || !active) return;
      const sessionId = getAuthSessionId();
      setToken(session.accessToken);
      setUser(session.user);
      void loadAiStatus(sessionId, () => active);
      await loadWorkspace();
      if (!active || !isAuthSessionCurrent(sessionId)) return;
      if (Number.isFinite(analysisIdParam) && analysisIdParam > 0) {
        try {
          const requestedAnalysis = await apiRequest<Analysis>(`/api/analysis-histories/${analysisIdParam}`);
          if (!active || !isAuthSessionCurrent(sessionId)) return;
          chooseResume(requestedAnalysis.resumeId);
          chooseJob(requestedAnalysis.jobDescriptionId);
          setAnalysis(requestedAnalysis);
          setMatchMode("report");
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
      const result = await signInRequest(apiRequest, authMode, auth);
      setAccessToken(result.accessToken);
      setToken(result.accessToken);
      setUser(result.user);
      void loadAiStatus(getAuthSessionId());
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

  useEffect(() => {
    const syncView = () => setPageView(viewFromHash(window.location.hash));
    const timer = window.setTimeout(syncView, 0);
    window.addEventListener("hashchange", syncView);
    return () => { window.clearTimeout(timer); window.removeEventListener("hashchange", syncView); };
  }, []);

  function openWorkbench() {
    activeAnalysisRun.current = null;
    setMatchMode("compose");
    navigate("match");
  }

  const effectiveJobsTotal = Math.max(jobsTotal, jobs.length);

  const workbenchNext = workbenchAction({
    resumeId: selectedResumeId,
    source: effectiveJobSource,
    jobId: selectedJobId,
    paste: pasteJob,
    latest: latestSelected,
  });
  const reportPanel = reportPanelState({
    workspace: workspaceStatus,
    analysis,
    analyzing: busy === "analysis",
    fetchingReport,
    fetchError: !!selectedAnalysisId && runner.reportFetchError?.analysisId === selectedAnalysisId,
    resumeCount: resumes.length,
    jobCount: jobs.length,
  });
  const workspaceLoading = workspaceStatus === "loading";
  const workspaceFailed = workspaceStatus === "error";

  if (!token) {
    return (
      <LoginPage
        mode={authMode}
        onModeChange={(mode) => { setAuthMode(mode); setError(""); }}
        form={auth}
        onFormChange={setAuth}
        busy={busy === "auth"}
        error={error}
        onSubmit={submitAuth}
        runtimeLabel={runtimeModeLabel(aiStatus)}
      />
    );
  }

  return (
    <AppShell
      title={pageView === "match" && matchMode === "compose" ? "岗位匹配工作台" : navLabel(pageView)}
      subtitle={pageView === "match" && matchMode === "compose" ? "选择你的简历与职位描述，开始一次新的匹配分析。" : VIEW_SUBTITLES[pageView]}
      hero
      activeView={pageView}
      user={user}
      onLogout={logout}
      onNavigate={navigate}
      actions={(
        <>
          <button className="ghost" type="button" onClick={() => { navigate("jobs"); editors.resetJobEditor(); editors.setShowJobEditor(true); }}>
            <Plus size={16} aria-hidden="true" />添加岗位
          </button>
          <button className="ghost" type="button" disabled={!!busy} onClick={() => void loadWorkspace()}>刷新数据</button>
        </>
      )}
    >
      {(error || notice) && (
        <div className={`toast ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>
          {error ? <CircleAlert size={20} aria-hidden="true" /> : <CircleCheck size={20} aria-hidden="true" />}
          {error || notice}
          <button aria-label="关闭通知" onClick={() => { setError(""); setNotice(""); }}>
            <X size={18} aria-hidden="true" />
          </button>
        </div>
      )}
      {workspaceFailed && (
        <div className="state-panel">
          <ErrorState title="工作台数据没有加载出来" text={workspaceError} onRetry={() => void loadWorkspace()} retrying={busy === "loading"} />
        </div>
      )}
      <section id="workflow" hidden={pageView !== "match" || workspaceFailed}>
        {matchMode === "compose" ? (
          <Workbench
            loading={workspaceLoading}
            resumes={resumes}
            jobs={jobs}
            moreJobs={Math.max(0, effectiveJobsTotal - jobs.length)}
            loadingMoreJobs={ranking.jobsLoadingMore}
            onLoadMoreJobs={() => void ranking.loadMoreJobs()}
            selectedResumeId={selectedResumeId}
            selectedJobId={selectedJobId}
            latestByJob={ranking.latestAnalysisByJob}
            source={effectiveJobSource}
            paste={pasteJob}
            action={workbenchNext}
            busy={!!busy}
            starting={busy === "paste-job" || busy === "analysis"}
            onChooseResume={chooseResume}
            onChooseJob={chooseJob}
            onSourceChange={setJobSource}
            onPasteChange={setPasteJob}
            onClear={() => { setPasteJob({ ...EMPTY_PASTE_JOB }); if (effectiveJobSource === "library") chooseJob(""); }}
            onAnalyze={() => void runner.startFromWorkbench()}
            onViewReport={() => setMatchMode("report")}
            onManageResumes={() => navigate("resumes")}
            onManageJobs={() => navigate("jobs")}
          />
        ) : (
          <MatchReportSection
            analysis={analysis}
            jobs={jobs}
            savedJobs={savedJobs}
            busy={busy}
            fetchingReport={fetchingReport}
            aiStatus={aiStatus}
            selectedResumeId={selectedResumeId}
            selectedJobId={selectedJobId}
            selectedJob={selectedJob}
            reportPanel={reportPanel}
            reportFetchErrorMessage={runner.reportFetchError?.message}
            onOpenWorkbench={openWorkbench}
            onBookmark={toggleBookmark}
            onExportMarkdown={runner.exportMarkdown}
            onExportPdf={runner.exportPdf}
            onSaveCopy={(copy) => {
              setResumes((items) => [copy, ...items.filter((item) => item.id !== copy.id)]);
              chooseResume(copy.id);
              invalidateSemanticMatches();
              setNotice("修改版已另存为新简历，原简历未覆盖；可重新分析对比。");
            }}
            onRunAnalysis={() => void runner.runAnalysis()}
            onRefreshWorkspace={() => void loadWorkspace()}
            onRetryReportFetch={runner.retryReportFetch}
            onNavigate={navigate}
          />
        )}
      </section>
      <ResumeLibrarySection
        hidden={pageView !== "resumes" || workspaceFailed}
        workspaceLoading={workspaceLoading}
        busy={busy}
        resumes={resumes}
        selectedResumeId={selectedResumeId}
        effectiveHistoryTotal={historyLib.effectiveHistoryTotal}
        pageOpenedAt={pageOpenedAt}
        fileInputRef={fileInputRef}
        file={editors.file}
        editingResumeId={editors.editingResumeId}
        resumeForm={editors.resumeForm}
        showResumeEditor={editors.showResumeEditor}
        resumeDragging={editors.resumeDragging}
        onSetResumeDragging={editors.setResumeDragging}
        onPickResumeFile={editors.pickResumeFile}
        onChooseFile={editors.chooseFile}
        onOpenPasteResume={editors.openPasteResume}
        onFillSampleForms={editors.fillSampleForms}
        onResetResumeEditor={editors.resetResumeEditor}
        onSaveResume={(event) => void editors.saveResume(event)}
        onResumeFormChange={editors.setResumeForm}
        onUseResume={(id) => { chooseResume(id); openWorkbench(); }}
        onBeginEditResume={(resume) => void editors.beginEditResume(resume)}
        onDeleteResume={(id) => void editors.deleteResume(id)}
      />
      <JobLibrarySection
        hidden={pageView !== "jobs" || workspaceFailed}
        workspaceLoading={workspaceLoading}
        busy={busy}
        resumes={resumes}
        selectedResumeId={selectedResumeId}
        jobs={jobs}
        libraryJobs={ranking.libraryJobs}
        effectiveJobsTotal={effectiveJobsTotal}
        selectedJobId={selectedJobId}
        savedJobs={savedJobs}
        jobsLoadingMore={ranking.jobsLoadingMore}
        jobSort={ranking.jobSort}
        jobFilter={ranking.jobFilter}
        topMatchCount={ranking.topMatchCount}
        jobAnalysesLoading={ranking.jobAnalysesLoading}
        jobAnalysesStatus={ranking.jobAnalysesStatus}
        jobAnalysesError={ranking.jobAnalysesError}
        jobSemanticStatus={ranking.jobSemanticStatus}
        jobSemanticError={ranking.jobSemanticError}
        jobAnalysisDataRequired={ranking.jobAnalysisDataRequired}
        jobSemanticDataRequired={ranking.jobSemanticDataRequired}
        libraryRankingReady={ranking.libraryRankingReady}
        latestAnalysisByJob={ranking.latestAnalysisByJob}
        semanticSimilarityByJob={ranking.semanticSimilarityByJob}
        showJobEditor={editors.showJobEditor}
        editingJobId={editors.editingJobId}
        jobForm={editors.jobForm}
        showBulkImport={editors.showBulkImport}
        bulkImportText={editors.bulkImportText}
        onJobSortChange={ranking.setJobSort}
        onJobFilterChange={ranking.setJobFilter}
        onTopMatchCountChange={ranking.setTopMatchCount}
        onLoadAllJobsForRanking={() => void ranking.loadAllJobsForRanking()}
        onRunTopMatchesAnalysis={() => void runner.runTopMatchesAnalysis()}
        onChooseResume={chooseResume}
        onResetJobEditor={editors.resetJobEditor}
        onSetShowJobEditor={editors.setShowJobEditor}
        onSetShowBulkImport={editors.setShowBulkImport}
        onSaveJob={(event) => void editors.saveJob(event)}
        onJobFormChange={editors.setJobForm}
        onBulkImportTextChange={editors.setBulkImportText}
        onBulkImportJobs={() => void editors.bulkImportJobs()}
        onSaveSampleAndAnalyze={() => { navigate("match"); void runner.saveSampleAndAnalyze(); }}
        onRetryJobAnalyses={(resumeId) => void ranking.loadJobAnalyses(resumeId)}
        onRetrySemanticMatches={(resumeId) => void ranking.loadSemanticMatches(resumeId)}
        onUseJob={(jobId) => { chooseJob(jobId); setJobSource("library"); openWorkbench(); }}
        onToggleBookmark={toggleBookmark}
        onBeginEditJob={editors.beginEditJob}
        onDeleteJob={(jobId) => void editors.deleteJob(jobId)}
        onLoadMoreJobs={() => void ranking.loadMoreJobs()}
      />
      <SavedJobsSection
        hidden={pageView !== "saved" || workspaceFailed}
        jobs={jobs}
        savedJobs={savedJobs}
        effectiveJobsTotal={effectiveJobsTotal}
        selectedJobId={selectedJobId}
        busy={busy}
        jobsLoadingMore={ranking.jobsLoadingMore}
        latestAnalysisByJob={ranking.latestAnalysisByJob}
        semanticSimilarityByJob={ranking.semanticSimilarityByJob}
        onGoToMatch={() => navigate("match")}
        onUseJob={(jobId) => { chooseJob(jobId); setJobSource("library"); openWorkbench(); }}
        onToggleBookmark={toggleBookmark}
        onLoadAllJobsForRanking={() => void ranking.loadAllJobsForRanking()}
      />
      <HistorySection
        hidden={pageView !== "history" || workspaceFailed}
        workspaceLoading={workspaceLoading}
        history={historyLib.history}
        visibleHistory={historyLib.visibleHistory}
        effectiveHistoryTotal={historyLib.effectiveHistoryTotal}
        hasMoreHistory={historyLib.hasMoreHistory}
        historyLoadingMore={historyLib.historyLoadingMore}
        historyRange={historyLib.historyRange}
        historySavedOnly={historyLib.historySavedOnly}
        pageOpenedAt={pageOpenedAt}
        resumes={resumes}
        jobs={jobs}
        savedJobs={savedJobs}
        onHistoryRangeChange={historyLib.setHistoryRange}
        onToggleHistorySavedOnly={() => historyLib.setHistorySavedOnly((value) => !value)}
        onResetFilters={() => { historyLib.setHistoryRange("all"); historyLib.setHistorySavedOnly(false); }}
        onOpenWorkbench={openWorkbench}
        openHistoryReport={runner.openHistoryReport}
        onLoadMoreHistory={() => void historyLib.loadMoreHistory()}
      />
    </AppShell>
  );
}
