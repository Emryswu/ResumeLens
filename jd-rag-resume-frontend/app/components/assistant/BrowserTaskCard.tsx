"use client";

import { useEffect, useState } from "react";
import { Ban, CircleCheck, Clock, LoaderCircle, ScanSearch } from "lucide-react";
import type { AgentBrowserTask, BrowserTaskOutcome, BrowserTaskProgress } from "../../agent-stream";
import { collectionLog, type CollectionActivity } from "../../collection-log";
import type { AgentTurn } from "../../agent-stream";

const PHASES: Record<string, (progress: BrowserTaskProgress) => string> = {
  connecting: () => "正在连接独立浏览器",
  opening: () => "正在打开 BOSS 搜索页",
  reading: (progress) => `正在读取第 ${progress.page ?? 1} 页`,
  waiting: (progress) => `随机等待几秒后读第 ${progress.page ?? 2} 页`,
  scrolling: (progress) => `正在加载第 ${progress.page ?? 2} 页`,
  read: (progress) => `已读完第 ${progress.page ?? 1} 页`,
  verifying: () => "正在核实完整职位描述",
};

/** A task the assistant handed to the browser extension. Only the active, unfinished card can be cancelled. */
export function BrowserTaskCard({ task, progress, facts, outcome, active, running: collecting, onCancel, onContinue }: {
  task: AgentBrowserTask;
  progress?: BrowserTaskProgress;
  facts?: CollectionActivity;
  outcome?: BrowserTaskOutcome["outcome"];
  active: boolean;
  onCancel: () => void;
  onContinue: () => void;
  running: boolean;
}) {
  const waiting = active && !outcome;
  const running = waiting && collecting;
  const [now, setNow] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const expired = now > 0 && task.expiresAt <= now;
  const detailTask = task.action === "verifyBossJob";
  const keyword = String(task.command.keyword ?? "");
  const city = String(task.command.city ?? "");
  const experience = String(task.command.experience ?? "不限");
  const pages = Number(task.command.maxPages ?? 1);
  const phase = progress ? PHASES[progress.phase]?.(progress) : null;
  const log = detailTask ? [] : collectionLog(facts ?? progress ?? {});
  const inProgress = !facts && progress != null && !progress.finishReason && !progress.stopReason;
  return (
    <div className={`agent-confirm${running ? " open" : ""}`} role="group" aria-label="浏览器采集任务">
      <ScanSearch className="agent-confirm-icon" size={20} aria-hidden="true" />
      <div className="agent-confirm-body">
        <h3>{detailTask ? "浏览器核实 BOSS 完整 JD" : "浏览器去 BOSS 采集岗位"}</h3>
        <p>{detailTask ? `岗位 ID：${task.command.jobId}` : <>{keyword ? `关键词「${keyword}」` : "不限岗位关键词"} · {city}{experience !== "不限" ? ` · 工作经验：${experience}` : ""} · 最多 {pages} 页</>}</p>
        <small>
          {detailTask ? "只读取指定岗位的详情页，不会联系 HR；成功核实后才会保存完整 JD。" : "只读取搜索列表，不会点击沟通或发送消息；存进职位库的是列表摘要，不是完整 JD。"}
          {(running || inProgress) && <> {phase ?? "正在联系浏览器"}{progress?.jobCount != null ? `，已读到 ${progress.jobCount} 条` : ""}。{running && task.expiresAt > 0 && <Countdown expiresAt={task.expiresAt} />}</>}
        </small>
        {log.length > 0 && <ol className="collection-log" aria-label="采集操作日志" aria-live="polite">
          {log.map((line, index) => <li key={index}>{line}</li>)}
        </ol>}
      </div>
      {waiting ? (
        <div className="agent-confirm-actions">
          {running ? <button className="ghost" type="button" onClick={onCancel}>取消采集</button>
            : <button className="ghost" type="button" onClick={onContinue}>{expired ? "结束已过期任务" : detailTask ? "继续核实" : "继续采集"}</button>}
        </div>
      ) : (
        <span className={`agent-confirm-result ${outcome === "completed" ? "approved" : outcome ? "rejected" : "none"}`}>
          {outcome === "completed" ? <><CircleCheck size={16} aria-hidden="true" />采集已返回结果</>
            : outcome === "cancelled" ? <><Ban size={16} aria-hidden="true" />已取消</>
              : outcome === "expired" ? <><Clock size={16} aria-hidden="true" />等待超时，已作废</>
                : <><LoaderCircle className="spin" size={16} aria-hidden="true" />等待中</>}
        </span>
      )}
    </div>
  );
}

/** A turn can collect several batches before verifying details; keep every completed batch visible. */
export function CompletedCollectionCards({ turn }: { turn: AgentTurn }) {
  return turn.steps.filter((step) => step.tool === "collect_boss_jobs" && step.ok && step.facts
    && step.toolCallId !== turn.browserTask?.toolCallId).map((step) => (
    <BrowserTaskCard key={step.toolCallId} task={{ toolCallId: step.toolCallId, tool: step.tool,
      taskId: step.toolCallId, executor: "browser-extension", action: "collectBossJobs",
      command: JSON.parse(step.arguments), expiresAt: 0, timeoutSeconds: 0 }}
      facts={step.facts!} outcome="completed" active={false} running={false} onCancel={() => {}} onContinue={() => {}} />
  ));
}

/** Server-side collectors report the same log fields through durable SSE events. */
export function RunningCollectorCards({ turn }: { turn: AgentTurn }) {
  return Object.values(turn.collectorProgress ?? {}).filter((item) => item.toolCallId !== turn.browserTask?.toolCallId
    && !turn.steps.some((step) => step.toolCallId === item.toolCallId)).map((item) => (
    <BrowserTaskCard key={item.toolCallId} task={{ toolCallId: item.toolCallId, tool: "collect_boss_jobs",
      taskId: item.toolCallId, executor: "patchright", action: item.command.mode === "detail" ? "verifyBossJob" : "collectBossJobs",
      command: item.command, expiresAt: 0, timeoutSeconds: 0 }} progress={{ ...item.progress, switchReason: item.switchReason }}
      active={false} running={false} onCancel={() => {}} onContinue={() => {}} />
  ));
}

/** Seconds left before the page stops waiting; counted from when the card first rendered. */
function Countdown({ expiresAt }: { expiresAt: number }) {
  const [left, setLeft] = useState(() => Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000)));
  useEffect(() => {
    const timer = setInterval(() => setLeft(Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))), 1000);
    return () => clearInterval(timer);
  }, [expiresAt]);
  return <>最多再等 {left} 秒。</>;
}
