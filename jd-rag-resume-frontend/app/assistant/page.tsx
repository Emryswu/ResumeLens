"use client";

import { ArrowUp, CircleAlert, LoaderCircle, MessageCircleQuestion, MessageSquarePlus, Sparkles } from "lucide-react";
import { FormEvent, KeyboardEvent, useEffect, useReducer, useRef, useState } from "react";
import { canRetryTurn } from "../agent-steps";
import { AppShell } from "../components/AppShell";
import { RetryButton } from "../components/StateBlock";
import { AgentMarkdown } from "../components/assistant/AgentMarkdown";
import { AnalysisCard } from "../components/assistant/AnalysisCard";
import { ConfirmationCard } from "../components/assistant/ConfirmationCard";
import { StepTimeline } from "../components/assistant/StepTimeline";
import { useSessionUser } from "../components/use-session-user";
import {
  agentChatReducer,
  initialAgentChatState,
  nextRequest,
  readAgentStream,
  type AgentConfirmation,
  type AgentTurn,
} from "../agent-stream";
import { analysisPollTimeoutMs, pollAnalysisUntilSettled } from "../analysis-poll";
import {
  ApiError,
  apiRequest,
  apiStream,
  getAuthSessionId,
  isAuthSessionCurrent,
  visibleApiErrorMessage,
  type AiStatus,
  type Analysis,
} from "../lib/api";

const EXAMPLES = [
  "我最新那份简历最适合投哪几个职位？",
  "排第一的职位，我的简历里有哪些证据能支撑？",
  "帮我对最合适的职位做一次完整分析",
];

const ERROR_COPY: Record<string, string> = {
  AGENT_RATE_LIMITED: "提问太频繁了，请稍后再试",
  AGENT_BUSY: "助手正忙，请稍后重试",
  AGENT_STEP_LIMIT: "这个问题步骤太多，请换个更具体的问法",
  AGENT_TIMEOUT: "本轮处理超时，请把问题拆小一些",
  AGENT_TRANSCRIPT_TOO_LARGE: "对话太长了，请点「新对话」重新开始",
  AGENT_STREAM_INTERRUPTED: "连接中断，本轮对话未完整接收。若已同意发起分析，请到分析记录确认结果。",
  AI_NOT_CONFIGURED: "后端尚未配置大模型",
};

export default function AssistantPage() {
  const { user, logout } = useSessionUser();
  const [state, dispatch] = useReducer(agentChatReducer, initialAgentChatState);
  const [draft, setDraft] = useState("");
  const [aiStatus, setAiStatus] = useState<AiStatus | null>(null);
  const [analyses, setAnalyses] = useState<Record<number, Analysis>>({});
  const abortRef = useRef<AbortController | null>(null);
  const followedRef = useRef<Set<number>>(new Set());
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let active = true;
    void apiRequest<AiStatus>("/api/ai/status", {}, { auth: false })
      .then((status) => {
        if (active) setAiStatus(status);
      })
      .catch(() => {
        // The badge is informational; stay neutral when the mode is unknown.
      });
    return () => {
      active = false;
      // Leaving the page closes the stream; the server stops the turn instead of spending more model calls.
      abortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [state.turns]);

  // Follow analyses the assistant started until the report is ready.
  useEffect(() => {
    for (const turn of state.turns) {
      const id = turn.analysisId;
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
  }, [state.turns, aiStatus]);

  async function send(body: unknown) {
    const controller = new AbortController();
    abortRef.current = controller;
    const sessionId = getAuthSessionId();
    let streamOpened = false;
    try {
      const stream = await apiStream("/api/agent/chat", body, controller.signal);
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
  }

  function ask(question: string) {
    const text = question.trim();
    if (!text || state.busy || state.pending) return;
    const body = nextRequest(state, { question: text });
    dispatch({ type: "ask", question: text });
    setDraft("");
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

  const inputLocked = state.busy || !!state.pending;

  return (
    <AppShell title="AI 求职助手" activeView="assistant" user={user} onLogout={logout}>
      <section className="assistant-shell">
        <div className="assistant-toolbar">
          <p className="assistant-mode">
            {aiStatus == null
              ? "助手会自己调用工具查询你的简历与职位库；涉及消耗配额的操作会先请你确认。"
              : aiStatus.mockEnabled
                ? "演示模式：由脚本模型按固定流程调用真实工具，未调用真实大模型。"
                : `当前模型：${aiStatus.model || "未配置"}。助手会自己调用工具；发起完整分析前会先请你确认。`}
          </p>
          <button
            className="ghost"
            type="button"
            disabled={state.busy || state.turns.length === 0}
            onClick={() => {
              followedRef.current = new Set();
              setAnalyses({});
              dispatch({ type: "reset" });
            }}
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
              analysis={turn.analysisId ? analyses[turn.analysisId] : undefined}
              onDecide={decide}
              onRetry={index === state.turns.length - 1 && !inputLocked && canRetryTurn(turn) ? () => ask(turn.question) : undefined}
              disabled={state.busy}
            />
          ))}
        </ol>
        <div className="assistant-bottom" ref={bottomRef} />

        <form className="assistant-input" onSubmit={submit}>
          <textarea
            rows={2}
            value={draft}
            placeholder={state.pending ? "请先确认或拒绝上面的操作" : "问点什么，例如：我的简历最适合哪个职位？（Enter 发送，Shift+Enter 换行）"}
            disabled={inputLocked}
            maxLength={4000}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
          />
          <button className="assistant-send" aria-label="发送" title={state.busy ? "处理中" : "发送"} disabled={inputLocked || !draft.trim()}>
            {state.busy
              ? <LoaderCircle className="spin" size={20} aria-hidden="true" />
              : <ArrowUp size={20} strokeWidth={2.25} aria-hidden="true" />}
          </button>
        </form>
      </section>
    </AppShell>
  );
}

function TurnView({ turn, live, pending, analysis, onDecide, onRetry, disabled }: {
  turn: AgentTurn;
  live: boolean;
  pending: AgentConfirmation | null;
  analysis?: Analysis;
  onDecide: (approved: boolean) => void;
  /** Only on the last failed turn, when asking the same question again is safe. */
  onRetry?: () => void;
  disabled: boolean;
}) {
  return (
    <li className="assistant-turn">
      <div className="assistant-question">{turn.question}</div>

      <StepTimeline turn={turn} pendingId={pending?.toolCallId ?? null} thinking={live} />

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
          <span>{ERROR_COPY[turn.error.code] ?? turn.error.message}</span>
          {onRetry && <RetryButton className="ghost compact" onClick={onRetry} />}
        </div>
      )}
    </li>
  );
}
