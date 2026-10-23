"use client";

import { useEffect, useState } from "react";
import { Ban, CircleCheck, Clock, LoaderCircle, ScanSearch } from "lucide-react";
import type { AgentBrowserTask, BrowserTaskOutcome, BrowserTaskProgress } from "../../agent-stream";

const PHASES: Record<string, (progress: BrowserTaskProgress) => string> = {
  opening: () => "正在打开 BOSS 搜索页",
  reading: (progress) => `正在读取第 ${progress.page ?? 1} 页`,
  waiting: (progress) => `随机等待几秒后读第 ${progress.page ?? 2} 页`,
  scrolling: (progress) => `正在加载第 ${progress.page ?? 2} 页`,
  read: (progress) => `已读完第 ${progress.page ?? 1} 页`,
};

/** A task the assistant handed to the browser extension. Only the active, unfinished card can be cancelled. */
export function BrowserTaskCard({ task, progress, outcome, active, running: collecting, onCancel, onContinue }: {
  task: AgentBrowserTask;
  progress?: BrowserTaskProgress;
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
  const keyword = String(task.command.keyword ?? "");
  const city = String(task.command.city ?? "");
  const experience = String(task.command.experience ?? "不限");
  const pages = Number(task.command.maxPages ?? 1);
  const phase = progress ? PHASES[progress.phase]?.(progress) : null;
  return (
    <div className={`agent-confirm${running ? " open" : ""}`} role="group" aria-label="浏览器插件任务">
      <ScanSearch className="agent-confirm-icon" size={20} aria-hidden="true" />
      <div className="agent-confirm-body">
        <h3>浏览器插件去 BOSS 采集岗位</h3>
        <p>{keyword ? `关键词「${keyword}」` : "不限岗位关键词"} · {city}{experience !== "不限" ? ` · 工作经验：${experience}` : ""} · 最多 {pages} 页</p>
        <small>
          插件只读取搜索列表，不会点击沟通或发送消息；存进职位库的是列表摘要，不是完整 JD。
          {running && <> {phase ?? "正在联系浏览器插件"}{progress?.jobCount != null ? `，已读到 ${progress.jobCount} 条` : ""}。<Countdown expiresAt={task.expiresAt} /></>}
        </small>
      </div>
      {waiting ? (
        <div className="agent-confirm-actions">
          {running ? <button className="ghost" type="button" onClick={onCancel}>取消采集</button>
            : <button className="ghost" type="button" onClick={onContinue}>{expired ? "结束已过期任务" : "继续采集"}</button>}
        </div>
      ) : (
        <span className={`agent-confirm-result ${outcome === "completed" ? "approved" : outcome ? "rejected" : "none"}`}>
          {outcome === "completed" ? <><CircleCheck size={16} aria-hidden="true" />插件已返回结果</>
            : outcome === "cancelled" ? <><Ban size={16} aria-hidden="true" />已取消</>
              : outcome === "expired" ? <><Clock size={16} aria-hidden="true" />等待超时，已作废</>
                : <><LoaderCircle className="spin" size={16} aria-hidden="true" />等待中</>}
        </span>
      )}
    </div>
  );
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
