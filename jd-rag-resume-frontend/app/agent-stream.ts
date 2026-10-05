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
  | "TIMED_OUT"
  | "FAILED"
  | "CANCELLED";

export type AgentEvent =
  | { type: "step"; data: AgentStep }
  | { type: "note"; data: { content: string } }
  | { type: "confirmation_required"; data: AgentConfirmation }
  | { type: "message"; data: { content: string } }
  | { type: "error"; data: { code: string; message: string } }
  | { type: "done"; data: { state: AgentDoneState; steps: number; transcript: AgentMessage[] } };

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

const EVENT_TYPES = new Set(["step", "note", "confirmation_required", "message", "error", "done"]);

export function toAgentEvent(frame: SseFrame): AgentEvent | null {
  if (!EVENT_TYPES.has(frame.event)) return null;
  try {
    return { type: frame.event, data: JSON.parse(frame.data) } as AgentEvent;
  } catch {
    return null;
  }
}

/** Reads the stream to the end, handing each known event to onEvent as it arrives. */
export async function readAgentStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: AgentEvent) => void,
): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  const parser = createSseParser();
  const deliver = (frames: SseFrame[]) => {
    for (const frame of frames) {
      const event = toAgentEvent(frame);
      if (event) onEvent(event);
    }
  };
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    deliver(parser.push(decoder.decode(value, { stream: true })));
  }
  deliver(parser.push(decoder.decode()));
  deliver(parser.flush());
}

/** What the page renders: one entry per user question, each with its own tool timeline. */
export type AgentTurn = {
  question: string;
  steps: AgentStep[];
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
  turns: AgentTurn[];
  busy: boolean;
  /** Present while the server waits for the user to approve or reject a write action. */
  pending: AgentConfirmation | null;
};

export type AgentChatAction =
  | { type: "ask"; question: string }
  | { type: "decide"; approved: boolean }
  | { type: "event"; event: AgentEvent }
  | { type: "failed"; code: string; message: string }
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
        turns: [...state.turns, { question: action.question, steps: [], notes: [] }],
      };
    case "decide":
      return {
        ...state,
        busy: true,
        pending: null,
        turns: updateLast(state.turns, (turn) => ({ ...turn, decision: action.approved ? "approved" : "rejected" })),
      };
    case "failed":
      return {
        ...state,
        busy: false,
        turns: updateLast(state.turns, (turn) => ({ ...turn, error: { code: action.code, message: action.message } })),
      };
    case "settled":
      return { ...state, busy: false };
    case "reset":
      return initialAgentChatState;
    case "event":
      return applyEvent(state, action.event);
  }
}

function applyEvent(state: AgentChatState, event: AgentEvent): AgentChatState {
  switch (event.type) {
    case "step":
      return {
        ...state,
        turns: updateLast(state.turns, (turn) => ({
          ...turn,
          steps: [...turn.steps, event.data],
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
        pending: event.data.state === "AWAITING_CONFIRMATION" ? state.pending : null,
      };
  }
}

/** The request body for the next call, built from state so it cannot drift from what was shown. */
export function nextRequest(state: AgentChatState, input: { question: string } | { approved: boolean }) {
  if ("question" in input) {
    return { messages: [...state.transcript, { role: "user", content: input.question }] };
  }
  if (!state.pending) throw new Error("no pending action to decide");
  return {
    messages: state.transcript,
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

function updateLast(turns: AgentTurn[], update: (turn: AgentTurn) => AgentTurn): AgentTurn[] {
  if (turns.length === 0) return turns;
  return [...turns.slice(0, -1), update(turns[turns.length - 1])];
}
