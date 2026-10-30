import { agentChatReducer, initialAgentChatState, nextRequest, readAgentStream } from "./agent-stream.ts";
import type { AgentChatState, AgentEvent, ReasoningEffort } from "./agent-stream.ts";
import { CONVERSATIONS } from "./conversation-client.ts";
import type { ConversationDetail, ConversationHeader } from "./conversation-client.ts";
import type { CollectorMode } from "./collector-mode";

type Input = ReturnType<typeof nextRequest>;
type IO = {
  request: <T>(path: string, options?: RequestInit) => Promise<T>;
  stream: (path: string, body: unknown, signal: AbortSignal) => Promise<ReadableStream<Uint8Array>>;
  current: (session: number) => boolean;
};

export type ConversationSnapshot = {
  scope: string;
  detail: ConversationDetail | null;
  items: ConversationHeader[];
  chat: AgentChatState;
  loading: boolean;
  error: string;
  /**
   * A browser task this page received live, with autoStart, in a turn whose stream reached done. Restored or
   * polled tasks never set it, so a reload or a second tab still waits for the user's click.
   */
  autoStartTaskId: string | null;
};

export const emptyConversationSnapshot: ConversationSnapshot = {
  scope: "", detail: null, items: [], chat: initialAgentChatState, loading: true, error: "", autoStartTaskId: null,
};

/** Owned by the root provider. Only presentation caches live here; durable history lives on the server. */
export class ConversationSession {
  private snapshot = emptyConversationSnapshot;
  private listeners = new Set<() => void>();
  private generation = 0;
  private sessionId = -1;
  private timer?: ReturnType<typeof setTimeout>;
  private controller?: AbortController;
  private posting = false;

  private io: IO;
  constructor(io: IO) { this.io = io; }

  getSnapshot = () => this.snapshot;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };

  private update(value: Partial<ConversationSnapshot>) {
    this.snapshot = { ...this.snapshot, ...value };
    this.listeners.forEach((listener) => listener());
  }

  clear = () => {
    this.generation++;
    clearTimeout(this.timer);
    this.controller?.abort();
    this.posting = false;
    this.snapshot = emptyConversationSnapshot;
    this.listeners.forEach((listener) => listener());
  };

  private owns(generation: number) { return generation === this.generation && this.io.current(this.sessionId); }

  open = async (userId: number, sessionId: number) => {
    const scope = `${userId}:${sessionId}`;
    if (this.snapshot.scope !== scope) {
      this.clear();
      this.sessionId = sessionId;
      this.update({ scope });
    }
    const generation = this.generation;
    if (this.posting) return;
    try {
      const items = await this.io.request<ConversationHeader[]>(CONVERSATIONS);
      if (!this.owns(generation)) return;
      this.update({ items });
      const id = this.snapshot.detail?.conversation.id ?? items[0]?.id;
      if (id != null) await this.select(id);
      else await this.create();
    } catch (error) { this.failed(generation, error); }
  };

  select = async (id: number) => {
    this.controller?.abort();
    this.posting = false;
    clearTimeout(this.timer);
    const generation = ++this.generation;
    this.update({ loading: true, error: "", detail: null, chat: initialAgentChatState, autoStartTaskId: null });
    try {
      const detail = await this.io.request<ConversationDetail>(`${CONVERSATIONS}/${id}`);
      if (this.owns(generation)) this.hydrate(detail);
    } catch (error) { this.failed(generation, error); }
  };

  create = async () => {
    const generation = ++this.generation;
    this.controller?.abort();
    this.posting = false;
    clearTimeout(this.timer);
    this.update({ loading: true, error: "", autoStartTaskId: null });
    try {
      const detail = await this.io.request<ConversationDetail>(CONVERSATIONS, { method: "POST" });
      if (!this.owns(generation)) return;
      const items = await this.io.request<ConversationHeader[]>(CONVERSATIONS);
      if (this.owns(generation)) {
        this.update({ items });
        this.hydrate(detail);
      }
    } catch (error) { this.failed(generation, error); }
  };

  remove = async (id: number) => {
    const generation = this.generation;
    try {
      await this.io.request(`${CONVERSATIONS}/${id}`, { method: "DELETE" });
      if (!this.owns(generation)) return;
      const items = this.snapshot.items.filter((item) => item.id !== id);
      this.update({ items });
      if (this.snapshot.detail?.conversation.id === id) {
        if (items[0]) await this.select(items[0].id);
        else await this.create();
      }
    } catch (error) { this.failed(generation, error); }
  };

  /** The switch lives on the server; the page shows what the server answered, never a local guess. */
  setFullAccess = async (fullAccess: boolean) => {
    const id = this.snapshot.detail?.conversation.id;
    const generation = this.generation;
    if (id == null) return;
    try {
      const header = await this.io.request<ConversationHeader>(`${CONVERSATIONS}/${id}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fullAccess }),
      });
      const detail = this.snapshot.detail;
      if (!this.owns(generation) || detail?.conversation.id !== id) return;
      const conversation = { ...detail.conversation, fullAccess: header.fullAccess };
      this.update({ detail: { ...detail, conversation },
        items: this.snapshot.items.map((item) => item.id === id ? { ...item, fullAccess: header.fullAccess } : item) });
    } catch (error) { this.failed(generation, error); }
  };

  /** The page calls this when it starts the task, so the same task is never started twice. */
  consumeAutoStart = (taskId: string) => {
    if (this.snapshot.autoStartTaskId === taskId) this.update({ autoStartTaskId: null });
  };

  private hydrate(detail: ConversationDetail) {
    const last = detail.turns.at(-1);
    const header = detail.conversation;
    const turns = detail.turns.map((turn) => {
      const previous = header.id === this.snapshot.detail?.conversation.id && turn.browserTask
        ? this.snapshot.chat.turns.find((item) => item.browserTask?.taskId === turn.browserTask?.taskId) : undefined;
      return previous?.browserProgress && !turn.browserProgress
        ? { ...turn, browserProgress: previous.browserProgress } : turn;
    });
    this.update({ detail, loading: false, error: "", chat: {
      ...initialAgentChatState,
      turns,
      busy: header.activeRun != null,
      pending: header.state === "AWAITING_CONFIRMATION" ? last?.confirmation ?? null : null,
      pendingBrowser: header.state === "AWAITING_BROWSER" ? last?.browserTask ?? null : null,
    }, items: [header, ...this.snapshot.items.filter((item) => item.id !== header.id)] });
    this.poll();
  }

  refresh = async () => {
    const id = this.snapshot.detail?.conversation.id;
    const generation = this.generation;
    if (id == null || this.posting) return;
    try {
      const after = this.snapshot.detail?.conversation.eventSequence ?? 0;
      const detail = await this.io.request<ConversationDetail>(`${CONVERSATIONS}/${id}?after=${after}`);
      if (this.owns(generation) && !this.posting) this.hydrate(detail);
    } catch (error) { this.failed(generation, error); }
  };

  private poll() {
    clearTimeout(this.timer);
    if (this.snapshot.chat.busy || this.snapshot.chat.pendingBrowser || this.snapshot.error) {
      this.timer = setTimeout(() => { void this.refresh(); }, 1000);
    }
  }

  private failed(generation: number, error: unknown) {
    if (!this.owns(generation)) return;
    this.update({ loading: false, error: error instanceof Error ? error.message : "读取会话失败" });
    if (this.snapshot.detail) this.poll();
  }

  progress = (event: Extract<Parameters<typeof agentChatReducer>[1], { type: "browser_progress" }>) => {
    this.update({ chat: agentChatReducer(this.snapshot.chat, event) });
  };

  send = async (input: Input, reasoningEffort: ReasoningEffort, collectorMode?: CollectorMode) => {
    const detail = this.snapshot.detail;
    if (!detail || this.posting || this.snapshot.chat.busy) return;
    const generation = this.generation;
    const controller = new AbortController();
    this.controller = controller;
    this.posting = true;
    clearTimeout(this.timer);
    this.update({ error: "", chat: { ...this.snapshot.chat, busy: true }, autoStartTaskId: null });
    let liveAutoStart: string | null = null;
    let pausedForBrowser = false;
    try {
      const stream = await this.io.stream("/api/agent/chat", { ...input, conversationId: detail.conversation.id, reasoningEffort,
        ...(collectorMode ? { collectorMode } : {}) }, controller.signal);
      if (!this.owns(generation)) { await stream.cancel(); return; }
      let chat = { ...this.snapshot.chat, busy: false };
      if ("question" in input) chat = agentChatReducer(chat, { type: "ask", question: input.question });
      else if ("approval" in input) chat = agentChatReducer(chat, { type: "decide", approved: input.approval.approved });
      else chat = agentChatReducer(chat, { type: "browser_report", outcome: input.browserResult.outcome });
      this.update({ chat });
      await readAgentStream(stream, (event: AgentEvent) => {
        if (!this.owns(generation)) return;
        if (event.type === "browser_task_required") liveAutoStart = event.data.autoStart ? event.data.taskId : null;
        if (event.type === "done") pausedForBrowser = event.data.state === "AWAITING_BROWSER";
        this.update({ chat: agentChatReducer(this.snapshot.chat, { type: "event", event }) });
      });
    } catch (error) {
      // A broken subscription says nothing about execution. Always reconcile before another input.
      if (!controller.signal.aborted && this.owns(generation)) this.update({ error: error instanceof Error ? error.message : "连接中断，正在同步会话" });
    } finally {
      if (this.owns(generation)) {
        this.posting = false;
        await this.refresh();
        // Only after posting ends, so the task's report can be sent as soon as the browser finishes.
        if (liveAutoStart && pausedForBrowser && this.owns(generation) && !this.snapshot.error
            && this.snapshot.chat.pendingBrowser?.taskId === liveAutoStart) this.update({ autoStartTaskId: liveAutoStart });
      }
    }
  };
}
