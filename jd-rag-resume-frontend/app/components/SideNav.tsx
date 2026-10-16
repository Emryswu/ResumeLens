"use client";

import Link from "next/link";
import { ArrowLeftRight, Bookmark, BriefcaseBusiness, FileText, History, Sparkles, type LucideIcon } from "lucide-react";
import { NAV_ITEMS, isHashView, type HashView, type NavIconName, type WorkspaceView } from "../workspace-nav";

const NAV_ICONS: Record<NavIconName, LucideIcon> = {
  match: ArrowLeftRight,
  resume: FileText,
  jobs: BriefcaseBusiness,
  saved: Bookmark,
  history: History,
  assistant: Sparkles,
};

export function SideNav({ active, onNavigate }: {
  active?: WorkspaceView;
  /** Set on the home page, which switches hash views in place instead of navigating. */
  onNavigate?: (view: HashView) => void;
}) {
  return (
    <aside className="app-sidebar">
      <Link href="/" className="brand"><span className="brand-mark">R</span><span className="brand-name">ResumeLens</span></Link>
      <nav aria-label="工作台导航">
        {NAV_ITEMS.map((item) => {
          const Icon = NAV_ICONS[item.icon];
          return <Link
            key={item.view}
            href={item.href}
            title={item.label}
            aria-current={active === item.view ? "page" : undefined}
            onClick={(event) => {
              const { view } = item;
              if (!onNavigate || !isHashView(view)) return;
              if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
              event.preventDefault();
              onNavigate(view);
            }}
          >
            <Icon className="nav-icon" size={20} strokeWidth={1.75} aria-hidden="true" />
            <span className="nav-label">{item.label}</span>
          </Link>;
        })}
      </nav>
    </aside>
  );
}
