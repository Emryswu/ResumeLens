"use client";

import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import type { User } from "../lib/api";
import type { HashView, WorkspaceView } from "../workspace-nav";
import { BackendStatus } from "./BackendStatus";
import { BrandImage } from "./BrandArt";
import { SideNav } from "./SideNav";

type Props = {
  children: ReactNode;
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  activeView?: WorkspaceView;
  /** Detail pages link back to the list they came from. */
  back?: { href: string; label: string };
  /** Paint the page header as the landscape banner with the slogan (the match view). */
  hero?: boolean;
  user: User | null;
  onLogout: () => void;
  onNavigate?: (view: HashView) => void;
};

/** The one layout every signed-in page uses: side navigation, top bar, page header, content. */
export function AppShell({ children, title, subtitle, actions, activeView, back, hero, user, onLogout, onNavigate }: Props) {
  const name = user?.displayName || user?.username;
  return (
    <div className="app-shell refined-workspace">
      <SideNav active={activeView} onNavigate={onNavigate} />
      <div className="app-main">
        <header className="app-topbar">
          {back && <Link className="app-back" href={back.href}><ArrowLeft size={16} aria-hidden="true" />{back.label}</Link>}
          <div className="app-account">
            <BackendStatus />
            {name && <span className="account-name" title={name}>{name}</span>}
            <button className="ghost" type="button" onClick={onLogout}>退出</button>
          </div>
        </header>
        <main className="workspace">
          <div className={`page-header${hero ? " page-hero" : ""}`}>
            {hero && <>
              <BrandImage name="hero-landscape" className="page-hero-art" />
              <BrandImage name="slogan" className="page-hero-slogan" alt="不只是匹配，更是看见你的潜力" />
            </>}
            <div className="page-heading">
              <h1 title={typeof title === "string" ? title : undefined}>{title}</h1>
              {subtitle && <p className="workspace-subtitle">{subtitle}</p>}
            </div>
            {actions && <div className="top-actions">{actions}</div>}
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}
