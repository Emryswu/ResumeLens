"use client";

import Link from "next/link";
import type { User } from "../lib/api";

export type WorkspaceView = "match" | "resumes" | "jobs" | "saved" | "history" | "assistant";

export function WorkspaceNav({ user, active, onLogout, onNavigate }: {
  user: User | null;
  active?: WorkspaceView;
  onLogout: () => void;
  onNavigate?: (view: WorkspaceView) => void;
}) {
  const links = [
    ["match", "/#workflow", "岗位匹配"],
    ["resumes", "/#resumes", "我的简历"],
    ["saved", "/#saved", "已保存"],
    ["history", "/#history", "分析记录"],
  ] as const;
  return <header className="workspace-nav">
    <Link href="/" className="brand"><span className="brand-mark">R</span><span>ResumeLens</span></Link>
    <nav aria-label="工作台导航">{links.map(([view, href, label]) =>
      <Link key={view} href={href} aria-current={active === view ? "page" : undefined} onClick={event => {
        if (onNavigate && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
          event.preventDefault();
          onNavigate(view);
        }
      }}>{label}</Link>,
    )}
    {/* A separate route, not a hash view: kept out of `links` so onNavigate never intercepts it. */}
    <Link href="/assistant" aria-current={active === "assistant" ? "page" : undefined}>AI 助手</Link>
    </nav>
    <div className="workspace-account"><span className="account-name">{user?.displayName || user?.username || "个人"}的工作空间</span>
      <span className="avatar" aria-hidden="true">{(user?.displayName || user?.username || "A").slice(0, 1).toUpperCase()}</span>
      <button className="ghost" type="button" onClick={onLogout}>退出</button>
    </div>
  </header>;
}
