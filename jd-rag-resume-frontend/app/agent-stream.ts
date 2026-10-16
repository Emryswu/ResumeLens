/** Wire types and pure state logic for the assistant page (POST /api/agent/chat, SSE). */

export type AgentToolCall = {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
};

/** OpenAI chat shape. The browser owns the transcript and sends it back every turn. */
export type AgentMessage = {
  role: "user" | "assistant" | "tool";
  content?: string | null;
  tool_calls?: AgentToolCall[];
  tool_call_id?: string;
};

export type AgentStep = {
  toolCallId: string;
  tool: string;
  arguments: string;
  ok: boolean;
  errorCode?: string | null;
  latencyMs: number;
  resultChars: number;
  resultPreview: string;
};

/** Sent when the server is about to run a tool; the matching step event carries the result. */
export type AgentStepStart = {
  toolCallId: string;
  tool: string;
  arguments: string;
};

export type AgentConfirmation = {
  toolCallId: string;
  tool: string;
  arguments: Record<string, unknown>;
  preview?: Record<string, unknown> | null;
};

export type AgentDoneState =
  | "COMPLETED"
  | "AWAITING_CONFIRMATION"
  | "STEP_LIMIT"
  | "REPLAN_LIMIT"
  | "TIMED_OUT"
  | "FAILED"
  | "CANCELLED";

export type AgentEvent =
  | { type: "step_started"; data: AgentStepStart }
  | { type: "step"; data: AgentStep }
  | { type: "note"; data: { content: string } }
  | { type: "confirmation_required"; data: AgentConfirmation }
  | { type: "message"; data: { content: string } }
  | { type: "error"; data: { code: string; message: string } }
  | { type: "done"; data: { state: AgentDoneState; steps: number; transcript: AgentMessage[]; summary?: string | null } };

export type SseFrame = { event: string; data: string };

/**
 * Incremental SSE parser. Chunks may split a frame, a line, or a CRLF pair anywhere;
 * multi-line data is joined with "\n"; comment lines (":") are ignored.
 */
export function createSseParser() {
  let buffer = "";
  let event = "";
  let data: string[] = [];

  function line(raw: string, frames: SseFrame[]) {
    if (raw === "") {
      if (data.length > 0) frames.push({ event: event || "message", data: data.join("\n") });
      event = "";
      data = [];
      return;
    }
    if (raw.startsWith(":")) return;
    const colon = raw.indexOf(":");
    const field = colon < 0 ? raw : raw.slice(0, colon);
    let value = colon < 0 ? "" : raw.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") event = value;
    else if (field === "data") data.push(value);
  }

  return {
    push(chunk: string): SseFrame[] {
      buffer += chunk;
      const frames: SseFrame[] = [];
      let start = 0;
      for (let index = 0; index < buffer.length; index += 1) {
        const char = buffer[index];
        if (char !== "\n" && char !== "\r") continue;
        // A trailing "\r" may be the first half of a CRLF still in flight.
        if (char === "\r" && index === buffer.length - 1) break;
        line(buffer.slice(start, index), frames);
        if (char === "\r" && buffer[index + 1] === "\n") index += 1;
        start = index + 1;
      }
      buffer = buffer.slice(start);
      return frames;
    },
    /** Dispatches a final frame the server ended without a blank line. */
    flush(): SseFrame[] {
      const frames: SseFrame[] = [];
      if (buffer) line(buffer.replace(/\r$/, ""), frames);
      buffer = "";
      line("", frames);
      return frames;
    },
  };
}

const EVENT_TYPES = new Set(["step_started", "step", "note", "confirmation_required", "message", "error", "done"]);
const DONE_STATES = new Set<AgentDoneState>([
  "COMPLETED", "AWAITING_CONFIRMATION", "STEP_LIMIT", "REPLAN_LIMIT", "TIMED_OUT", "FAILED", "CANCELLED",
]);
const RETRYABLE_REJECTIONS = new Set(["AGENT_RATE_LIMITED", "AGENT_BUSY", "AI_NOT_CONFIGURED"]);

export function toAgentEvent(frame: SseFrame): AgentEvent | null {
  if (!EVENT_TYPES.has(frame.event)) return null;
  try {
    const data = JSON.parse(frame.data);
    // A damaged done frame must not make an incomplete transcript look usable.
    if (frame.event === "done" && (!data || !DONE_STATES.has(data.state)
        || !Number.isInteger(data.steps) || data.steps < 0 || !Array.isArray(data.transcript))) return null;
    return { type: frame.event, data } as AgentEvent;
  } catch {
    return null;
  }
}

export class AgentStreamInterruptedError extends Error {
  constructor() {
    super("助手连接中断，本轮对话未完整接收");
    this.name = "AgentStreamInterruptedError";
  }
}

/** Delivers events through the terminal done frame; EOF before done means the turn is incomplete. */
export async function readAgentStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: AgentEvent) => void,
): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  const parser = createSseParser();
  let sawDone = false;
  const deliver = (frames: SseFrame[]) => {
    for (const frame of frames) {
      const event = toAgentEvent(frame);
      if (event) {
        if (event.type === "done") sawDone = true;
        onEvent(event);
        if (sawDone) break;
      }
    }
  };
  try {
    while (!sawDone) {
      const { value, done } = await reader.read();
      if (done) break;
      deliver(parser.push(decoder.decode(value, { stream: true })));
    }
    if (!sawDone) {
      deliver(parser.push(decoder.decode()));
      deliver(parser.flush());
    }
    if (!sawDone) throw new AgentStreamInterruptedError();
  } finally {
    if (sawDone) {
      // Nothing after done belongs to this turn; do not wait for a proxy to close the connection.
      await reader.cancel().catch(() => {});
    }
    reader.releaseLock();
  }
}

/** What the page renders: one entry per user question, each with its own tool timeline. */
export type AgentTurn = {
  question: string;
  steps: AgentStep[];
  /** Calls the server started, in order; a call stays running until its step event arrives. */
  started: AgentStepStart[];
  /** Started calls whose request ended without a result. They are shown as unfinished, never as done. */
  unfinished: string[];
  notes: string[];
  answer?: string;
  error?: { code: string; message: string };
  confirmation?: AgentConfirmation;
  decision?: "approved" | "rejected";
  /** Set when start_analysis ran; the page follows it to the finished report. */
  analysisId?: number;
};

export type AgentChatState = {
  transcript: AgentMessage[];
  summary?: string;
  turns: AgentTurn[];
  busy: boolean;
  /** Present while the server waits for the user to approve or reject a write action. */
  pending: AgentConfirmation | null;
};

export type AgentChatAction =
  | { type: "ask"; question: string }
  | { type: "decide"; approved: boolean }
  | { type: "event"; event: AgentEvent }
  | { type: "failed"; code: string; message: string; retryConfirmation?: boolean }
  | { type: "settled" }
  | { type: "reset" };

export const initialAgentChatState: AgentChatState = { transcript: [], turns: [], busy: false, pending: null };

export function agentChatReducer(state: AgentChatState, action: AgentChatAction): AgentChatState {
  switch (action.type) {
    case "ask":
      return {
        ...state,
        busy: true,
        pending: null,
        turns: [...state.turns, { question: action.question, steps: [], started: [], unfinished: [], notes: [] }],
      };
    case "decide":
      return {
        ...state,
        busy: true,
        // Keep the confirmed transcript and card until the server acknowledges the decision.
        turns: updateLast(state.turns, (turn) => ({ ...turn, error: undefined })),
      };
    case "failed":
      return {
        ...state,
        busy: false,
        // Only a definite HTTP rejection is safe to retry. A broken stream may have already executed the write.
        pending: action.retryConfirmation && RETRYABLE_REJECTIONS.has(action.code) ? state.pending : null,
        turns: updateLast(state.turns, (turn) => ({ ...closeOpenSteps(turn), error: { code: action.code, message: action.message } })),
      };
    case "settled":
      // Whatever ended the request, nothing can still be running afterwards.
      return { ...state, busy: false, turns: updateLast(state.turns, closeOpenSteps) };
    case "reset":
      return initialAgentChatState;
    case "event":
      return applyEvent(state, action.event);
  }
}

function applyEvent(state: AgentChatState, event: AgentEvent): AgentChatState {
  switch (event.type) {
    case "step_started":
      return {
        ...state,
        turns: updateLast(state.turns, (turn) => turn.started.some((item) => item.toolCallId === event.data.toolCallId)
          ? turn
          : { ...turn, started: [...turn.started, event.data] }),
      };
    case "step":
      return {
        ...state,
        pending: state.pending?.toolCallId === event.data.toolCallId ? null : state.pending,
        turns: updateLast(state.turns, (turn) => ({
          ...turn,
          steps: [...turn.steps, event.data],
          decision: turn.confirmation?.toolCallId === event.data.toolCallId
            ? event.data.errorCode === "USER_REJECTED" ? "rejected" : "approved"
            : turn.decision,
          analysisId: event.data.tool === "start_analysis" && event.data.ok
            ? startedAnalysisId(event.data.resultPreview) ?? turn.analysisId
            : turn.analysisId,
        })),
      };
    case "note":
      return { ...state, turns: updateLast(state.turns, (turn) => ({ ...turn, notes: [...turn.notes, event.data.content] })) };
    case "message":
      return { ...state, turns: updateLast(state.turns, (turn) => ({ ...turn, answer: event.data.content })) };
    case "error":
      return { ...state, turns: updateLast(state.turns, (turn) => ({ ...turn, error: event.data })) };
    case "confirmation_required":
      return {
        ...state,
        pending: event.data,
        turns: updateLast(state.turns, (turn) => ({ ...turn, confirmation: event.data, decision: undefined })),
      };
    case "done":
      // Even a failed turn returns a consistent transcript; keep it so the next question has context.
      return {
        ...state,
        busy: false,
        transcript: event.data.transcript,
        summary: event.data.summary ?? undefined,
        pending: event.data.state === "AWAITING_CONFIRMATION" ? state.pending : null,
        turns: updateLast(state.turns, closeOpenSteps),
      };
  }
}

/** The request body for the next call, built from state so it cannot drift from what was shown. */
export function nextRequest(state: AgentChatState, input: { question: string } | { approved: boolean }) {
  if (state.busy) throw new Error("assistant request is in progress");
  if ("question" in input) {
    return { messages: [...state.transcript, { role: "user", content: input.question }],
      ...(state.summary ? { summary: state.summary } : {}) };
  }
  if (!state.pending) throw new Error("no pending action to decide");
  return {
    messages: state.transcript,
    ...(state.summary ? { summary: state.summary } : {}),
    approval: { toolCallId: state.pending.toolCallId, approved: input.approved },
  };
}

function startedAnalysisId(preview: string): number | undefined {
  try {
    const id = Number((JSON.parse(preview) as { analysisId?: unknown }).analysisId);
    return Number.isFinite(id) && id > 0 ? id : undefined;
  } catch {
    return undefined;
  }
}

/** Marks every started call without a result as unfinished. */
function closeOpenSteps(turn: AgentTurn): AgentTurn {
  const open = turn.started
    .map((item) => item.toolCallId)
    .filter((id) => !turn.steps.some((step) => step.toolCallId === id) && !turn.unfinished.includes(id));
  return open.length === 0 ? turn : { ...turn, unfinished: [...turn.unfinished, ...open] };
}

function updateLast(turns: AgentTurn[], update: (turn: AgentTurn) => AgentTurn): AgentTurn[] {
  if (turns.length === 0) return turns;
  return [...turns.slice(0, -1), update(turns[turns.length - 1])];
}
