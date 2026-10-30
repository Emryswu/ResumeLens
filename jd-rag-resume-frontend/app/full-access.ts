import type { AgentChatState } from "./agent-stream";

export const FULL_ACCESS = {
  label: "完全访问",
  description: "采集搜索列表、核实详情页直接执行，不再逐次点「继续」。每日页数和等待间隔照常限制，放宽求职条件仍需你确认。",
} as const;

export type AssistantInput = {
  /** Sending, the option menus and the send button. */
  sendLocked: boolean;
  /** The text box itself; with 完全访问 the user can draft the next question while collection runs. */
  draftLocked: boolean;
  placeholder: string;
};

/**
 * {@code autoRunningTaskId} is the browser task this page started on its own (完全访问). Only that task leaves the
 * text box open; a task waiting for the user's click, or one the user started, keeps today's lock.
 */
export function assistantInput(chat: Pick<AgentChatState, "busy" | "pending" | "pendingBrowser">,
  { loading, error, autoRunningTaskId }: { loading: boolean; error: boolean; autoRunningTaskId: string | null }): AssistantInput {
  const sendLocked = loading || error || chat.busy || !!chat.pending || !!chat.pendingBrowser;
  const autoRunning = chat.pendingBrowser != null && chat.pendingBrowser.taskId === autoRunningTaskId;
  return {
    sendLocked,
    draftLocked: sendLocked && !(autoRunning && !loading && !error && !chat.busy && !chat.pending),
    placeholder: chat.pending ? "请先确认或拒绝上面的操作"
      : autoRunning ? "浏览器正在自动执行，可以先写下一个问题，结束后再发送"
        : chat.pendingBrowser ? "请先继续或结束上面的浏览器采集任务"
          : "问点什么，例如：我的简历最适合哪个职位？（Enter 发送，Shift+Enter 换行）",
  };
}
