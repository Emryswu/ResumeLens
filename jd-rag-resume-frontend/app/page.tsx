"use client";

import { ArrowUp, Brain, CircleAlert, FlaskConical, LoaderCircle, MessageCircleQuestion, MessageSquarePlus, Plus, Sparkles, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import { AppShell } from "./components/AppShell";
import { RetryButton } from "./components/StateBlock";
import { ChoiceMenu } from "./components/ChoiceMenu";
import { AgentMarkdown } from "./components/assistant/AgentMarkdown";
import { AnalysisCard } from "./components/assistant/AnalysisCard";
import { BrowserTaskCard, CompletedCollectionCards, RunningCollectorCards } from "./components/assistant/BrowserTaskCard";
import { ConfirmationCard } from "./components/assistant/ConfirmationCard";
import { LoginPage, type AuthForm, type AuthMode } from "./components/LoginPage";
import { ProcessDrawer, StepTimeline } from "./components/assistant/StepTimeline";
import { useSessionUser } from "./components/use-session-user";
import {
  nextRequest,
  retryRequest,
  REASONING_EFFORT_KEY,
  savedReasoningEffort,
  type AgentBrowserTask,
  type AgentChatState,
  type AgentConfirmation,
  type AgentTurn,
  type ReasoningEffort,
} from "./agent-stream";
import { runExtensionCollect } from "./extension-collect";
import { COLLECTOR_OPTIONS, collectorStorageKey, readCollectorModes, type CollectorMode } from "./collector-mode";
import { analysisPollTimeoutMs, pollAnalysisUntilSettled } from "./analysis-poll";
import { runtimeModeLabel } from "./runtime-mode";
import { modelDisplayName, REASONING_OPTIONS } from "./agent-model-display";
import { saveSampleData, submitAnalysis } from "./sample-data";
import { signInRequest } from "./sign-in";
import { legacyWorkspaceUrl, workspaceHref, PREFERENCES_PATH } from "./workspace-nav";
import {
  apiRequest,
  getAuthSessionId,
  isAuthSessionCurrent,
  setAccessToken,
  visibleApiErrorMessage,
  type AiStatus,
  type Analysis,
  type PageData,
  type Resume,
  type User,
} from "./lib/api";

import { useConversation } from "./components/ConversationProvider";

const EXAMPLES = [
  "我最新那份简历最适合投哪几个职位？",
  "去 BOSS 搜深圳的 Java 后端岗位，看两页",
  "排第一的职位，我的简历里有哪些证据能支撑？",
  "帮我对最合适的职位做一次完整分析",
];

const ERROR_COPY: Record<string, string> = {
  AGENT_RATE_LIMITED: "提问太频繁了，请稍后再试",
  AGENT_BUSY: "助手正忙，请稍后重试",
  AGENT_STEP_LIMIT: "本轮查询已达到步骤上限，已根据已有结果收尾",
  AGENT_TIMEOUT: "本轮处理超时，请把问题拆小一些",
  AGENT_TRANSCRIPT_TOO_LARGE: "对话太长了，请点「新对话」重新开始",
  AGENT_STREAM_INTERRUPTED: "连接中断，本轮对话未完整接收。若已同意发起分析，请到分析记录确认结果。",
  AI_NOT_CONFIGURED: "后端尚未配置大模型",
};

/** The home page: the assistant, or the sign-in form when there is no session. */
export default function HomePage() {
  const router = useRouter();
  const { user, status, logout, signedIn } = useSessionUser({ signInHere: true });
  const [aiStatus, setAiStatus] = useState<AiStatus | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [auth, setAuth] = useState<AuthForm>({ username: "", password: "", email: "", displayName: "" });
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState("");

  // The workbench used to live here: old links (bookmarks, an extension popup not yet updated) go on to it.
  useEffect(() => {
    const target = legacyWorkspaceUrl(window.location.search, window.location.hash);
    if (target) router.replace(target);
  }, [router]);

  useEffect(() => {
    let active = true;
    void apiRequest<AiStatus>("/api/ai/status", {}, { auth: false })
      .then((next) => {
        if (active) setAiStatus(next);
      })
      .catch(() => {
        // The badge is informational; stay neutral when the mode is unknown.
      });
    return () => {
      active = false;
    };
  }, []);

  async function submitAuth(event: FormEvent) {
    event.preventDefault();
    setAuthBusy(true);
    setAuthError("");
    try {
      const result = await signInRequest(apiRequest, authMode, auth);
      setAccessToken(result.accessToken);
      signedIn(result.user);
    } catch (reason) {
      setAuthError(visibleApiErrorMessage(reason, "登录失败") ?? "");
    } finally {
      setAuthBusy(false);
    }
  }

  if (status !== "signedIn" || !user) {
    return (
      <LoginPage
        mode={authMode}
        onModeChange={(mode) => { setAuthMode(mode); setAuthError(""); }}
        form={auth}
        onFormChange={setAuth}
        busy={authBusy}
        error={authError}
        onSubmit={submitAuth}
        runtimeLabel={runtimeModeLabel(aiStatus)}
      />
    );
  }
  // Keyed by user; the root provider additionally scopes cached data to authSessionId.
  return <Assistant key={user.id} user={user} onLogout={logout} aiStatus={aiStatus} />;
}

type SampleRun =
  | { status: "saving" }
  | { status: "started"; analysisId: number }
  | { status: "failed"; message: string };

function Assistant({ user, onLogout: logout, aiStatus }: { user: User; onLogout: () => void; aiStatus: AiStatus | null }) {
  const { session, snapshot } = useConversation(user.id);
  const state = snapshot.chat;
  const dispatchProgress = session.progress;
  /** The turn whose process is open in the side panel. */
  const [processTurn, setProcessTurn] = useState<number | null>(null);
  const closeProcess = useCallback(() => setProcessTurn(null), []);
  const [draft, setDraft] = useState("");
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>(() =>
    typeof window === "undefined" ? "high" : savedReasoningEffort(window.localStorage.getItem(REASONING_EFFORT_KEY)));
  const [collectorModes, setCollectorModes] = useState<Record<number, CollectorMode>>(() => typeof window === "undefined"
    ? {} : readCollectorModes(window.localStorage.getItem(collectorStorageKey(user.id))));
  const conversationId = snapshot.detail?.conversation.id;
  const collectorMode = conversationId == null ? "auto" : collectorModes[conversationId] ?? "auto";
  /** Null until known; zero shows the "prepare a resume first" card. */
  const [resumeCount, setResumeCount] = useState<number | null>(null);
  const [sampleRun, setSampleRun] = useState<SampleRun | null>(null);
  const [analyses, setAnalyses] = useState<Record<number, Analysis>>({});
  const followedRef = useRef<Set<number>>(new Set());
  const bottomRef = useRef<HTMLDivElement | null>(null);
  /** Aborting it cancels the browser task in progress (the user's 取消, 新对话, or leaving the page). */
  const browserAbortRef = useRef<AbortController | null>(null);
  const browserTaskRef = useRef<string | null>(null);
  const stateRef = useRef<AgentChatState>(state);
  useEffect(() => {
    stateRef.current = state;
  });

  async function newConversation() {
    browserTaskRef.current = null;
    browserAbortRef.current?.abort();
    setProcessTurn(null);
    await session.create();
  }

  function switchConversation(id: number) {
    browserTaskRef.current = null;
    browserAbortRef.current?.abort();
    setProcessTurn(null);
    void session.select(id);
  }

  useEffect(() => {
    let active = true;
    const sessionId = getAuthSessionId();
    void apiRequest<PageData<Resume>>("/api/resumes?size=1")
      .then((page) => {
        if (active && isAuthSessionCurrent(sessionId)) setResumeCount(page.totalElements);
      })
      .catch(() => {
        // Without the count the examples still work; only the onboarding card is skipped.
      });
    return () => {
      active = false;
      // Detach collection locally; the persisted task remains available for explicit continuation.
      browserTaskRef.current = null;
      browserAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [state.turns]);

  // Follow analyses the assistant (or the sample button) started until the report is ready.
  useEffect(() => {
    const started = state.turns.map((turn) => turn.analysisId);
    if (sampleRun?.status === "started") started.push(sampleRun.analysisId);
    for (const id of started) {
      if (!id || followedRef.current.has(id)) continue;
      followedRef.current.add(id);
      const sessionId = getAuthSessionId();
      void apiRequest<Analysis>(`/api/analysis-histories/${id}`)
        .then((initial) => pollAnalysisUntilSettled(initial, {
          fetchById: (analysisId) => apiRequest<Analysis>(`/api/analysis-histories/${analysisId}`),
          timeoutMs: analysisPollTimeoutMs(aiStatus?.pendingTimeoutMinutes),
          onProgress: (current) => {
            if (isAuthSessionCurrent(sessionId)) setAnalyses((previous) => ({ ...previous, [id]: current }));
          },
          shouldContinue: () => isAuthSessionCurrent(sessionId),
        }))
        .catch(() => {
          // The report link still works; the inline status just stops updating.
        });
    }
  }, [state.turns, sampleRun, aiStatus]);

  const send = useCallback((body: ReturnType<typeof nextRequest>) => session.send(body, reasoningEffort, collectorMode), [session, reasoningEffort, collectorMode]);
  const [browserRunning, setBrowserRunning] = useState<string | null>(null);

  async function continueBrowser() {
    const task = state.pendingBrowser;
    if (!task || state.busy || browserTaskRef.current === task.taskId) return;
    browserTaskRef.current = task.taskId;
    const controller = new AbortController();
    browserAbortRef.current = controller;
    const sessionId = getAuthSessionId();
    setBrowserRunning(task.taskId);
    const outcome = await runExtensionCollect({ task, target: window, signal: controller.signal,
      onProgress: (progress) => {
        if (isAuthSessionCurrent(sessionId) && browserTaskRef.current === task.taskId) dispatchProgress({ type: "browser_progress", progress });
      },
    });
    if (browserTaskRef.current !== task.taskId || !isAuthSessionCurrent(sessionId)) return;
    browserTaskRef.current = null;
    setBrowserRunning(null);
    const current = stateRef.current;
    if (!current.busy && current.pendingBrowser?.taskId === task.taskId) await send(nextRequest(current, { browser: outcome }));
  }

  /** Same sample as the workbench's 「保存示例并分析」: saves a resume and a job, then starts a full analysis. */
  async function trySample() {
    if (sampleRun?.status === "saving" || sampleRun?.status === "started") return;
    setSampleRun({ status: "saving" });
    const sessionId = getAuthSessionId();
    try {
      const { resume, job } = await saveSampleData(apiRequest);
      if (!isAuthSessionCurrent(sessionId)) return;
      setResumeCount((count) => (count ?? 0) + 1);
      const analysis = await submitAnalysis(apiRequest, resume.id, job.id);
      if (!isAuthSessionCurrent(sessionId)) return;
      setAnalyses((previous) => ({ ...previous, [analysis.id]: analysis }));
      setSampleRun({ status: "started", analysisId: analysis.id });
    } catch (reason) {
      const message = visibleApiErrorMessage(reason, "示例数据没有保存成功");
      if (message != null && isAuthSessionCurrent(sessionId)) setSampleRun({ status: "failed", message });
    }
  }

  function ask(question: string) {
    const text = question.trim();
    if (snapshot.loading || snapshot.error || !text || state.busy || state.pending || state.pendingBrowser) return;
    setDraft("");
    void send(nextRequest(state, { question: text }));
  }

  /** A manual retry asks again only after reconciliation with server state. */
  function retry() {
    const request = retryRequest(state);
    if (request) void send(request.body);
  }

  function decide(approved: boolean) {
    if (!state.pending || state.busy) return;
    const body = nextRequest(state, { approved });
    void send(body);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    ask(draft);
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      ask(draft);
    }
  }

  const inputLocked = snapshot.loading || !!snapshot.error || state.busy || !!state.pending || !!state.pendingBrowser;
  const canRetry = retryRequest(state) != null;
  const displayModel = modelDisplayName(aiStatus?.model);
  const effortLabel = REASONING_OPTIONS.find((option) => option.value === reasoningEffort)!.label;

  return (
    <AppShell title="AI 求职助手" activeView="assistant" user={user} onLogout={() => { session.clear(); logout(); }}>
      <section className="assistant-shell">
        <div className="assistant-toolbar">
          <p className="assistant-mode">
            {aiStatus == null
              ? "助手会自己调用工具查询你的简历与职位库；涉及消耗配额的操作会先请你确认。"
              : aiStatus.mockEnabled
                ? "演示模式：由脚本模型按固定流程调用真实工具，未调用真实大模型。"
                : `当前模型：${modelDisplayName(aiStatus.model)}。助手会自己调用工具；发起完整分析前会先请你确认。`}
          </p>
          <Link className="ghost" href={PREFERENCES_PATH}>我的求职条件</Link>
          <button
            className="ghost"
            type="button"
            disabled={snapshot.loading}
            onClick={() => void newConversation()}
          >
            <MessageSquarePlus size={16} aria-hidden="true" />新对话
          </button>
        </div>

        <nav className="conversation-history" aria-label="历史会话">
          {snapshot.items.map((item) => <div className="conversation-history-item" key={item.id}>
            <button type="button" className="ghost compact" aria-current={item.id === snapshot.detail?.conversation.id ? "true" : undefined}
              onClick={() => switchConversation(item.id)}>{item.title}{item.activeRun ? " · 运行中" : ""}</button>
            <button type="button" className="ghost compact" aria-label={`删除会话 ${item.title}`} disabled={item.activeRun != null}
              onClick={() => void session.remove(item.id)}>删除</button>
          </div>)}
        </nav>
        {snapshot.loading && <p role="status">正在读取会话…</p>}
        {snapshot.error && <div className="assistant-error" role="alert">{snapshot.error}<button type="button" className="ghost compact" onClick={() => void session.open(user.id, getAuthSessionId())}>重新同步</button></div>}
        {state.busy && <p role="status">这一轮仍在运行，结果会自动同步。</p>}
        {!snapshot.loading && state.turns.length === 0 && (
          <div className="assistant-empty">
            <div className="assistant-empty-head">
              <span className="state-icon" aria-hidden="true"><Sparkles size={22} /></span>
              <div>
                <h2>还没有对话，可以这样问我</h2>
                <p>点一个例子直接提问，或在下方输入你的问题。</p>
              </div>
            </div>
            {(resumeCount === 0 || sampleRun) && (
              <div className="assistant-onboarding">
                <div>
                  <strong>先准备一份简历</strong>
                  <p>助手的回答都基于你保存的简历和职位。可以上传自己的简历，也可以先用示例数据走一遍完整分析。</p>
                </div>
                <div className="assistant-onboarding-actions">
                  <Link className="secondary" href={workspaceHref("resumes")}><Upload size={16} aria-hidden="true" />上传简历</Link>
                  <button
                    type="button"
                    className="ghost"
                    disabled={sampleRun?.status === "saving" || sampleRun?.status === "started"}
                    onClick={() => void trySample()}
                  >
                    {sampleRun?.status === "saving"
                      ? <LoaderCircle className="spin" size={16} aria-hidden="true" />
                      : <FlaskConical size={16} aria-hidden="true" />}
                    试试示例数据
                  </button>
                </div>
                {sampleRun?.status === "failed" && <div className="assistant-error" role="alert"><CircleAlert size={16} aria-hidden="true" />{sampleRun.message}</div>}
                {sampleRun?.status === "started" && (
                  <AnalysisCard analysisId={sampleRun.analysisId} analysis={analyses[sampleRun.analysisId]} />
                )}
              </div>
            )}
            <div className="assistant-examples">
              {EXAMPLES.map((example) => (
                <button key={example} type="button" className="ghost" onClick={() => ask(example)}>
                  <MessageCircleQuestion size={16} aria-hidden="true" />{example}
                </button>
              ))}
            </div>
          </div>
        )}

        <ol className="assistant-turns">
          {state.turns.map((turn, index) => (
            <TurnView
              key={index}
              turn={turn}
              live={index === state.turns.length - 1 && state.busy}
              pending={index === state.turns.length - 1 ? state.pending : null}
              pendingBrowser={index === state.turns.length - 1 ? state.pendingBrowser : null}
              onCancelBrowser={() => browserAbortRef.current?.abort()}
              onContinueBrowser={() => void continueBrowser()}
              browserRunning={browserRunning === turn.browserTask?.taskId}
              analysis={turn.analysisId ? analyses[turn.analysisId] : undefined}
              onDecide={decide}
              onRetry={index === state.turns.length - 1 && canRetry ? retry : undefined}
              disabled={state.busy}
              processOpen={processTurn === index}
              onOpenProcess={() => setProcessTurn(index)}
            />
          ))}
        </ol>
        {processTurn != null && state.turns[processTurn] && (
          <ProcessDrawer
            turn={state.turns[processTurn]}
            turnKey={processTurn}
            pendingId={processTurn === state.turns.length - 1 ? state.pending?.toolCallId ?? null : null}
            onClose={closeProcess}
          />
        )}
        <div className="assistant-bottom" ref={bottomRef} />

        <form className="assistant-input" onSubmit={submit}>
          <textarea
            rows={2}
            value={draft}
            placeholder={state.pending ? "请先确认或拒绝上面的操作"
                : state.pendingBrowser ? "请先继续或结束上面的浏览器采集任务"
                  : "问点什么，例如：我的简历最适合哪个职位？（Enter 发送，Shift+Enter 换行）"}
            disabled={inputLocked}
            maxLength={4000}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
          />
          <div className="assistant-input-actions">
            <ChoiceMenu label={`采集方式：${COLLECTOR_OPTIONS.find((option) => option.value === collectorMode)!.label.replace("采集方式：", "")}`}
              value={collectorMode} options={COLLECTOR_OPTIONS} disabled={inputLocked || conversationId == null}
              onChange={(mode) => {
                const next = { ...collectorModes, [conversationId!]: mode };
                window.localStorage.setItem(collectorStorageKey(user.id), JSON.stringify(next));
                setCollectorModes(next);
              }}>
              <Plus size={17} aria-hidden="true" />
            </ChoiceMenu>
            <ChoiceMenu label={`模型与思考档位：${displayModel}，${effortLabel}`}
              value={reasoningEffort} options={REASONING_OPTIONS} disabled={inputLocked}
              onChange={(effort) => {
                window.localStorage.setItem(REASONING_EFFORT_KEY, effort);
                setReasoningEffort(effort);
              }}>
              <Brain size={16} aria-hidden="true" />
              <span>{displayModel}</span>
              <span className="choice-menu-separator" aria-hidden="true">·</span>
              <span>{effortLabel}</span>
            </ChoiceMenu>
            <button className="assistant-send" aria-label="发送" title={state.busy ? "处理中" : "发送"} disabled={inputLocked || !draft.trim()}>
              {state.busy
                ? <LoaderCircle className="spin" size={20} aria-hidden="true" />
                : <ArrowUp size={20} strokeWidth={2.25} aria-hidden="true" />}
            </button>
          </div>
        </form>
      </section>
    </AppShell>
  );
}

function TurnView({
  turn, live, pending, pendingBrowser, onCancelBrowser, onContinueBrowser, browserRunning, analysis, onDecide, onRetry, disabled, processOpen, onOpenProcess,
}: {
  turn: AgentTurn;
  live: boolean;
  pending: AgentConfirmation | null;
  pendingBrowser: AgentBrowserTask | null;
  onCancelBrowser: () => void;
  onContinueBrowser: () => void;
  browserRunning: boolean;
  analysis?: Analysis;
  onDecide: (approved: boolean) => void;
  /** Only on the last failed turn, when asking the same question again is safe. */
  onRetry?: () => void;
  disabled: boolean;
  processOpen: boolean;
  onOpenProcess: () => void;
}) {
  return (
    <li className="assistant-turn">
      <div className="assistant-question">{turn.question}</div>

      <StepTimeline turn={turn} pendingId={pending?.toolCallId ?? null} thinking={live} open={processOpen} onOpen={onOpenProcess} />

      {/* Text the model writes next to its tool calls; before a confirmation it often carries the actual conclusion. */}
      {turn.notes.map((note, index) => <AgentMarkdown key={index} className="assistant-note" text={note} />)}

      {turn.confirmation && (
        <ConfirmationCard
          confirmation={turn.confirmation}
          decision={turn.decision}
          approvalSent={turn.approvedCallId === turn.confirmation.toolCallId}
          active={pending?.toolCallId === turn.confirmation.toolCallId}
          disabled={disabled}
          onDecide={onDecide}
        />
      )}

      <CompletedCollectionCards turn={turn} />
      <RunningCollectorCards turn={turn} />
      {turn.browserTask && (
        <BrowserTaskCard
          task={turn.browserTask}
          progress={turn.collectorProgress?.[turn.browserTask.toolCallId]
            ? { ...turn.collectorProgress[turn.browserTask.toolCallId].progress, switchReason: turn.collectorProgress[turn.browserTask.toolCallId].switchReason }
            : turn.browserProgress}
          facts={turn.steps.find((step) => step.toolCallId === turn.browserTask?.toolCallId)?.facts ?? undefined}
          outcome={turn.browserOutcome}
          active={pendingBrowser?.taskId === turn.browserTask.taskId}
          onCancel={onCancelBrowser}
          onContinue={onContinueBrowser}
          running={browserRunning}
        />
      )}

      {turn.analysisId && <AnalysisCard analysisId={turn.analysisId} analysis={analysis} />}

      {turn.answer && (
        <section className="assistant-reply" aria-label="回答">
          <h3><Sparkles size={16} aria-hidden="true" />回答</h3>
          <AgentMarkdown className="assistant-answer" text={turn.answer} />
        </section>
      )}
      {turn.error && (
        <div className="assistant-error" role="alert">
          <CircleAlert size={18} aria-hidden="true" />
          <span>
            {ERROR_COPY[turn.error.code] ?? turn.error.message}

          </span>
          {onRetry && <RetryButton className="ghost compact" onClick={onRetry} />}

        </div>
      )}
    </li>
  );
}
