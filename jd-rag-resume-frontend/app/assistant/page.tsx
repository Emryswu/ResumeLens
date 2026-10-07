"use client";

import Link from "next/link";
import { FormEvent, KeyboardEvent, useEffect, useMemo, useReducer, useRef, useState } from "react";
import { AppChrome } from "../components/AppChrome";
import { parseAgentMarkdown, type MdInline } from "../agent-markdown";
import {
  agentChatReducer,
  initialAgentChatState,
  nextRequest,
  readAgentStream,
  type AgentConfirmation,
  type AgentStep,
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

const TOOL_LABELS: Record<string, string> = {
  list_resumes: "读取简历列表",
  search_jobs: "搜索职位库",
  get_job: "读取职位详情",
  rank_jobs_for_resume: "按语义相似度排序职位",
  search_resume_evidence: "检索简历证据（RAG）",
  get_latest_analysis: "读取最近一次分析",
  start_analysis: "发起完整分析",
};

const ERROR_COPY: Record<string, string> = {
  AGENT_RATE_LIMITED: "提问太频繁了，请稍后再试",
  AGENT_BUSY: "助手正忙，请稍后重试",
  AGENT_STEP_LIMIT: "这个问题步骤太多，请换个更具体的问法",
  AGENT_TIMEOUT: "本轮处理超时，请把问题拆小一些",
  AGENT_TRANSCRIPT_TOO_LARGE: "对话太长了，请点「新对话」重新开始",
  AI_NOT_CONFIGURED: "后端尚未配置大模型",
};

export default function AssistantPage() {
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
    try {
      const stream = await apiStream("/api/agent/chat", body, controller.signal);
      await readAgentStream(stream, (event) => {
        if (isAuthSessionCurrent(sessionId)) dispatch({ type: "event", event });
      });
    } catch (reason) {
      if (controller.signal.aborted) return;
      const message = visibleApiErrorMessage(reason, "助手请求失败");
      if (message == null) return;
      const code = reason instanceof ApiError ? reason.code : "NETWORK_ERROR";
      dispatch({ type: "failed", code, message: ERROR_COPY[code] ?? message });
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
    <AppChrome
      title="AI 求职助手"
      eyebrow="TOOL-CALLING AGENT"
      activeView="assistant"
      actions={
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
          新对话
        </button>
      }
    >
      <section className="assistant-shell">
        <p className="assistant-mode">
          {aiStatus == null
            ? "助手会自己调用工具查询你的简历与职位库；涉及消耗配额的操作会先请你确认。"
            : aiStatus.mockEnabled
              ? "演示模式：由脚本模型按固定流程调用真实工具，未调用真实大模型。"
              : `当前模型：${aiStatus.model || "未配置"}。助手会自己调用工具；发起完整分析前会先请你确认。`}
        </p>

        {state.turns.length === 0 && (
          <div className="assistant-empty">
            <h2>可以这样问我</h2>
            <div className="assistant-examples">
              {EXAMPLES.map((example) => (
                <button key={example} type="button" className="ghost" onClick={() => ask(example)}>
                  {example}
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
              disabled={state.busy}
            />
          ))}
        </ol>
        <div ref={bottomRef} />

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
          <button className="secondary" disabled={inputLocked || !draft.trim()}>
            {state.busy ? "处理中…" : "发送"}
          </button>
        </form>
      </section>
    </AppChrome>
  );
}

function TurnView({ turn, live, pending, analysis, onDecide, disabled }: {
  turn: AgentTurn;
  live: boolean;
  pending: AgentConfirmation | null;
  analysis?: Analysis;
  onDecide: (approved: boolean) => void;
  disabled: boolean;
}) {
  return (
    <li className="assistant-turn">
      <div className="assistant-question">{turn.question}</div>

      {(turn.steps.length > 0 || live) && (
        <ol className="agent-timeline" aria-label="工具调用时间线">
          {turn.steps.map((step) => <StepView key={step.toolCallId} step={step} />)}
          {live && <li className="agent-step running"><span className="agent-step-dot" />思考中…</li>}
        </ol>
      )}

      {/* Text the model writes next to its tool calls; before a confirmation it often carries the actual conclusion. */}
      {turn.notes.map((note, index) => <AgentMarkdown key={index} className="assistant-note" text={note} />)}

      {turn.confirmation && (
        <ConfirmationCard
          confirmation={turn.confirmation}
          decision={turn.decision}
          active={pending?.toolCallId === turn.confirmation.toolCallId}
          disabled={disabled}
          onDecide={onDecide}
        />
      )}

      {turn.analysisId && <AnalysisStatus analysisId={turn.analysisId} analysis={analysis} />}

      {turn.answer && <AgentMarkdown className="assistant-answer" text={turn.answer} />}
      {turn.error && <div className="assistant-error">{ERROR_COPY[turn.error.code] ?? turn.error.message}</div>}
    </li>
  );
}

function StepView({ step }: { step: AgentStep }) {
  return (
    <li className={`agent-step ${step.ok ? "ok" : "failed"}`}>
      <details>
        <summary>
          <span className="agent-step-dot" aria-hidden="true" />
          <strong>{TOOL_LABELS[step.tool] ?? step.tool}</strong>
          <code>{describeArguments(step.arguments)}</code>
          <span className="agent-step-meta">
            {step.ok ? `${step.latencyMs} ms` : step.errorCode}
          </span>
        </summary>
        <div className="agent-step-detail">
          <div><span>工具</span><code>{step.tool}</code></div>
          <div><span>参数</span><code>{step.arguments}</code></div>
          <div><span>结果</span><code>{step.resultChars} 字符 · {step.resultPreview || "（空）"}</code></div>
        </div>
      </details>
    </li>
  );
}

function ConfirmationCard({ confirmation, decision, active, disabled, onDecide }: {
  confirmation: AgentConfirmation;
  decision?: "approved" | "rejected";
  active: boolean;
  disabled: boolean;
  onDecide: (approved: boolean) => void;
}) {
  const preview = confirmation.preview ?? {};
  const resumeTitle = String(preview.resumeTitle ?? `简历 #${String(confirmation.arguments.resumeId ?? "")}`);
  const jobTitle = String(preview.jobTitle ?? `职位 #${String(confirmation.arguments.jobId ?? "")}`);
  const company = preview.companyName ? `（${String(preview.companyName)}）` : "";
  return (
    <div className="agent-confirm" role="group" aria-label="待确认的操作">
      <div>
        <span className="eyebrow">需要你确认</span>
        <p>
          助手想要{TOOL_LABELS[confirmation.tool] ?? confirmation.tool}：<strong>{resumeTitle}</strong> × <strong>{jobTitle}{company}</strong>
        </p>
        <small>这会消耗一次分析配额并调用一次大模型。</small>
      </div>
      {active && !decision ? (
        <div className="agent-confirm-actions">
          <button className="secondary" type="button" disabled={disabled} onClick={() => onDecide(true)}>同意并发起</button>
          <button className="ghost" type="button" disabled={disabled} onClick={() => onDecide(false)}>拒绝</button>
        </div>
      ) : (
        <span className={`agent-confirm-result ${decision === "approved" ? "approved" : ""}`}>
          {decision === "approved" ? "已同意" : decision === "rejected" ? "已拒绝" : "未确认"}
        </span>
      )}
    </div>
  );
}

function AnalysisStatus({ analysisId, analysis }: { analysisId: number; analysis?: Analysis }) {
  const href = analysis
    ? `/?resumeId=${analysis.resumeId}&jobId=${analysis.jobDescriptionId}&analysisId=${analysisId}`
    : `/?analysisId=${analysisId}`;
  return (
    <div className="agent-analysis">
      <span>
        {!analysis || analysis.status === "PENDING"
          ? "完整分析生成中…"
          : analysis.status === "COMPLETED"
            ? `分析完成，匹配分 ${analysis.matchScore ?? "—"}`
            : `分析失败：${analysis.summary ?? "未知原因"}`}
      </span>
      <Link className="ghost compact" href={href}>查看完整报告</Link>
    </div>
  );
}

function AgentMarkdown({ text, className }: { text: string; className: string }) {
  const blocks = useMemo(() => parseAgentMarkdown(text), [text]);
  return (
    <div className={`${className} agent-md`}>
      {blocks.map((block, index) => {
        switch (block.type) {
          case "paragraph":
            return (
              <p key={index}>
                {block.lines.map((line, lineIndex) => (
                  <span key={lineIndex}>{lineIndex > 0 && <br />}<Inline parts={line} /></span>
                ))}
              </p>
            );
          case "heading":
            return <p key={index} className={`agent-md-heading level-${block.level}`}><Inline parts={block.content} /></p>;
          case "list": {
            const items = block.items.map((item, itemIndex) => <li key={itemIndex}><Inline parts={item} /></li>);
            return block.ordered ? <ol key={index} start={block.start}>{items}</ol> : <ul key={index}>{items}</ul>;
          }
          case "table":
            return (
              <div key={index} className="agent-md-table">
                <table>
                  <thead>
                    <tr>{block.header.map((cell, cellIndex) => <th key={cellIndex}><Inline parts={cell} /></th>)}</tr>
                  </thead>
                  <tbody>
                    {block.rows.map((row, rowIndex) => (
                      <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}><Inline parts={cell} /></td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          case "code":
            return <pre key={index}><code>{block.text}</code></pre>;
        }
      })}
    </div>
  );
}

function Inline({ parts }: { parts: MdInline[] }) {
  return parts.map((part, index) =>
    part.type === "strong" ? <strong key={index}>{part.text}</strong>
      : part.type === "code" ? <code key={index}>{part.text}</code>
        : part.text,
  );
}

function describeArguments(raw: string): string {
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const parts = Object.entries(parsed).map(([key, value]) => `${key}=${String(value)}`);
    return parts.length > 0 ? parts.join(" ") : "无参数";
  } catch {
    return raw;
  }
}
