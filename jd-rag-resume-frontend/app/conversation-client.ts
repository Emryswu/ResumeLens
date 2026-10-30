import type { AgentChatState } from "./agent-stream";

export type ConversationHeader = {
  id: number;
  title: string;
  state: string;
  activeRun: string | null;
  eventSequence: number;
  updatedAt: string;
  /** 完全访问, held by the server for this conversation. */
  fullAccess?: boolean;
};

export type ConversationEvent = {
  sequence: number;
  turnId: string;
  runId: string;
  type: string;
  data: Record<string, unknown>;
};

export type ConversationDetail = {
  conversation: ConversationHeader;
  turns: AgentChatState["turns"];
  events: ConversationEvent[];
};

export const CONVERSATIONS = "/api/agent/conversations";
