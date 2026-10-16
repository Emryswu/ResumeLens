"use client";

import Link from "next/link";
import { NAV_ITEMS, isHashView, type HashView, type WorkspaceView } from "../workspace-nav";
import { NavIcon } from "./NavIcon";

export function SideNav({ active, onNavigate }: {
  active?: WorkspaceView;
  /** Set on the home page, which switches hash views in place instead of navigating. */
  onNavigate?: (view: HashView) => void;
}) {
  return (
    <aside className="app-sidebar">
      <Link href="/" className="brand"><span className="brand-mark">R</span><span className="brand-name">ResumeLens</span></Link>
      <nav aria-label="工作台导航">
        {NAV_ITEMS.map((item) => (
          <Link
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
            <NavIcon name={item.icon} />
            <span className="nav-label">{item.label}</span>
          </Link>
        ))}
      </nav>
    </aside>
  );
}
