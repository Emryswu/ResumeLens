"use client";

import { ArrowRight, ArrowUp, Brain, CircleAlert, FlaskConical, LoaderCircle, MessageCircleQuestion, MessageSquarePlus, Sparkles, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useReducer, useRef, useState } from "react";
import { AppShell } from "./components/AppShell";
import { RetryButton } from "./components/StateBlock";
import { ChoiceMenu } from "./components/ChoiceMenu";
import { AgentMarkdown } from "./components/assistant/AgentMarkdown";
import { AnalysisCard } from "./components/assistant/AnalysisCard";
import { BrowserTaskCard } from "./components/assistant/BrowserTaskCard";
import { ConfirmationCard } from "./components/assistant/ConfirmationCard";
import { LoginPage, type AuthForm, type AuthMode } from "./components/LoginPage";
import { ProcessDrawer, StepTimeline } from "./components/assistant/StepTimeline";
import { useSessionUser } from "./components/use-session-user";
import {
  agentChatReducer,
  initialAgentChatState,
  nextRequest,
  readAgentStream,
  retryAdvice,
  retryRequest,
  REASONING_EFFORT_KEY,
  savedReasoningEffort,
  staleConversation,
  type AgentBrowserTask,
  type AgentChatState,
  type AgentConfirmation,
  type AgentTurn,
  type ReasoningEffort,
} from "./agent-stream";
import { runExtensionCollect } from "./extension-collect";
import { analysisPollTimeoutMs, pollAnalysisUntilSettled } from "./analysis-poll";
import { runtimeModeLabel } from "./runtime-mode";
import { modelDisplayName, REASONING_OPTIONS } from "./agent-model-display";
import { saveSampleData, submitAnalysis } from "./sample-data";
import { signInRequest } from "./sign-in";
import { legacyWorkspaceUrl, reportHref, workspaceHref } from "./workspace-nav";
import {
  ApiError,
  apiRequest,
  apiStream,
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

import { CONVERSATIONS, type ConversationHeader, type ConversationDetail } from "./conversation-client";

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
  // Keyed by user: signing out unmounts the conversation, which also stops its stream and browser task.
  return <Assistant key={user.id} user={user} onLogout={logout} aiStatus={aiStatus} />;
}

type SampleRun =
  | { status: "saving" }
  | { status: "started"; analysisId: number }
  | { status: "failed"; message: string };

function Assistant({ user, onLogout: logout, aiStatus }: { user: User; onLogout: () => void; aiStatus: AiStatus | null }) {
  const [state, dispatch] = useReducer(agentChatReducer, initialAgentChatState);
  const [conversationId, setConversationId] = useState<number | null>(null);
  useEffect(() => {
    let active = true;
    const sessionId = getAuthSessionId();
    void (async () => {
      const conversations = await apiRequest<ConversationHeader[]>(CONVERSATIONS);
      const recent = conversations[0];
      const detail = recent ? await apiRequest<ConversationDetail>(`${CONVERSATIONS}/${recent.id}`)
        : await apiRequest<ConversationDetail>(CONVERSATIONS, { method: "POST" });
      if (active && isAuthSessionCurrent(sessionId)) setConversationId(detail.conversation.id);
    })().catch((reason) => {
      if (active && isAuthSessionCurrent(sessionId)) dispatch({ type: "failed", code: "CONVERSATION_LOAD_FAILED", message: visibleApiErrorMessage(reason, "读取会话失败") ?? "读取会话失败" });
    });
    return () => { active = false; };
  }, []);

  /** The turn whose process is open in the side panel. */
  const [processTurn, setProcessTurn] = useState<number | null>(null);
  const closeProcess = useCallback(() => setProcessTurn(null), []);
  const [draft, setDraft] = useState("");
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>(() =>
    typeof window === "undefined" ? "high" : savedReasoningEffort(window.localStorage.getItem(REASONING_EFFORT_KEY)));
  /** Null until known; zero shows the "prepare a resume first" card. */
  const [resumeCount, setResumeCount] = useState<number | null>(null);
  const [sampleRun, setSampleRun] = useState<SampleRun | null>(null);
  const [analyses, setAnalyses] = useState<Record<number, Analysis>>({});
  const abortRef = useRef<AbortController | null>(null);
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
    const sessionId = getAuthSessionId();
    const detail = await apiRequest<ConversationDetail>(CONVERSATIONS, { method: "POST" });
    if (!isAuthSessionCurrent(sessionId)) return;
    browserTaskRef.current = null;
    browserAbortRef.current?.abort();
    setConversationId(detail.conversation.id);
    dispatch({ type: "reset" });
    setProcessTurn(null);
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
      // Leaving the page closes the stream; the server stops the turn instead of spending more model calls.
      abortRef.current?.abort();
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

  const send = useCallback(async (body: ReturnType<typeof nextRequest>) => {
    const controller = new AbortController();
    abortRef.current = controller;
    const sessionId = getAuthSessionId();
    let streamOpened = false;
    try {
      if (conversationId == null) throw new Error("会话尚未加载");
      const { messages, summary: _summary, ...input } = body;
      const question = "approval" in input || "browserResult" in input ? {} : { question: messages.at(-1)?.content };
      const stream = await apiStream("/api/agent/chat", { ...input, ...question, conversationId, reasoningEffort }, controller.signal);
      streamOpened = true;
      await readAgentStream(stream, (event) => {
        if (isAuthSessionCurrent(sessionId)) dispatch({ type: "event", event });
      });
    } catch (reason) {
      if (controller.signal.aborted) return;
      const message = visibleApiErrorMessage(reason, "助手请求失败");
      if (message == null) return;
      const code = streamOpened ? "AGENT_STREAM_INTERRUPTED" : reason instanceof ApiError ? reason.code : "NETWORK_ERROR";
      dispatch({
        type: "failed", code, message: ERROR_COPY[code] ?? message,
        retryConfirmation: !streamOpened && reason instanceof ApiError && reason.status >= 400,
      });
    } finally {
      dispatch({ type: "settled" });
    }
  }, [reasoningEffort, conversationId]);

  // A turn that ended waiting on the browser: run the task through the extension, then report whatever happened.
  useEffect(() => {
    const task = state.pendingBrowser;
    if (!task || state.busy || browserTaskRef.current === task.taskId) return;
    browserTaskRef.current = task.taskId;
    const controller = new AbortController();
    browserAbortRef.current = controller;
    const sessionId = getAuthSessionId();
    void runExtensionCollect({
      task,
      target: window,
      signal: controller.signal,
      onProgress: (progress) => {
        if (isAuthSessionCurrent(sessionId)) dispatch({ type: "browser_progress", progress });
      },
    }).then((outcome) => {
      const current = stateRef.current;
      // Leaving or resetting invalidates ownership before abort resolves, even before React renders again.
      if (browserTaskRef.current !== task.taskId || !isAuthSessionCurrent(sessionId)
          || current.busy || current.pendingBrowser?.taskId !== task.taskId) return;
      const body = nextRequest(current, { browser: outcome });
      dispatch({ type: "browser_report", outcome: outcome.outcome });
      void send(body);
    });
  }, [state.pendingBrowser, state.busy, send]);

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
    // nextRequest also refuses a stale conversation; checking first keeps the draft instead of throwing.
    if (!conversationId || !text || state.busy || state.pending || state.pendingBrowser || staleConversation(state)) return;
    setDraft("");
    start(text, nextRequest(state, { question: text }));
  }

  /** Sends exactly what retryRequest built; it refuses whenever the transcript would predate a confirmed write. */
  function retry() {
    const request = retryRequest(state);
    if (request) start(request.question, request.body);
  }

  function start(question: string, body: ReturnType<typeof nextRequest>) {
    dispatch({ type: "ask", question });
    void send(body);
  }

  function decide(approved: boolean) {
    if (!state.pending || state.busy) return;
    const body = nextRequest(state, { approved });
    dispatch({ type: "decide", approved });
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

  const stale = staleConversation(state);
  const inputLocked = conversationId == null || state.busy || !!state.pending || !!state.pendingBrowser || !!stale;
  const canRetry = retryRequest(state) != null;
  const displayModel = modelDisplayName(aiStatus?.model);
  const effortLabel = REASONING_OPTIONS.find((option) => option.value === reasoningEffort)!.label;

  return (
    <AppShell title="AI 求职助手" activeView="assistant" user={user} onLogout={logout}>
      <section className="assistant-shell">
        <div className="assistant-toolbar">
          <p className="assistant-mode">
            {aiStatus == null
              ? "助手会自己调用工具查询你的简历与职位库；涉及消耗配额的操作会先请你确认。"
              : aiStatus.mockEnabled
                ? "演示模式：由脚本模型按固定流程调用真实工具，未调用真实大模型。"
                : `当前模型：${modelDisplayName(aiStatus.model)}。助手会自己调用工具；发起完整分析前会先请你确认。`}
          </p>
          <button
            className="ghost"
            type="button"
            disabled={state.busy || state.turns.length === 0}
            onClick={() => void newConversation()}
          >
            <MessageSquarePlus size={16} aria-hidden="true" />新对话
          </button>
        </div>

        {state.turns.length === 0 && (
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
            placeholder={stale ? "这段对话的记录没有同步完整，不能继续提问：请查看上面的报告入口，或点「新对话」重新开始"
              : state.pending ? "请先确认或拒绝上面的操作"
                : state.pendingBrowser ? "浏览器插件正在采集，完成或取消后才能继续提问"
                  : "问点什么，例如：我的简历最适合哪个职位？（Enter 发送，Shift+Enter 换行）"}
            disabled={inputLocked}
            maxLength={4000}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
          />
          <div className="assistant-input-actions">
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
  turn, live, pending, pendingBrowser, onCancelBrowser, analysis, onDecide, onRetry, disabled, processOpen, onOpenProcess,
}: {
  turn: AgentTurn;
  live: boolean;
  pending: AgentConfirmation | null;
  pendingBrowser: AgentBrowserTask | null;
  onCancelBrowser: () => void;
  analysis?: Analysis;
  onDecide: (approved: boolean) => void;
  /** Only on the last failed turn, when asking the same question again is safe. */
  onRetry?: () => void;
  disabled: boolean;
  processOpen: boolean;
  onOpenProcess: () => void;
}) {
  const advice = retryAdvice(turn);
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

      {turn.browserTask && (
        <BrowserTaskCard
          task={turn.browserTask}
          progress={turn.browserProgress}
          outcome={turn.browserOutcome}
          active={pendingBrowser?.taskId === turn.browserTask.taskId}
          onCancel={onCancelBrowser}
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
            {advice.kind === "check-report" && (
              <span className="assistant-error-hint">
                {advice.analysisId
                  ? "分析已经发起，但对话记录没有同步完整。为避免助手误以为没有执行，这段对话不能继续，请直接查看已生成的报告，或点「新对话」重新开始。"
                  : "你已同意发起分析，但无法确定是否已经执行。为避免重复发起，这段对话不能继续，请先到分析记录核实，或点「新对话」重新开始。"}
              </span>
            )}
          </span>
          {onRetry && <RetryButton className="ghost compact" onClick={onRetry} />}
          {advice.kind === "check-report" && (
            <Link className="ghost compact" href={advice.analysisId ? reportHref({ analysisId: advice.analysisId }) : workspaceHref("history")}>
              {advice.analysisId ? "查看报告" : "去分析记录"}<ArrowRight size={14} aria-hidden="true" />
            </Link>
          )}
        </div>
      )}
    </li>
  );
}
