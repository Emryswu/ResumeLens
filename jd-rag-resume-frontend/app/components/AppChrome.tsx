"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import {
  AUTH_EXPIRED_EVENT,
  logoutSession,
  refreshSession,
  type User,
} from "../lib/api";
import { BackendStatus } from "./BackendStatus";
import { WorkspaceNav, type WorkspaceView } from "./WorkspaceNav";

type Props = {
  children: ReactNode;
  title: string;
  eyebrow?: string;
  actions?: ReactNode;
  activeView?: WorkspaceView;
};

export function AppChrome({ children, title, eyebrow = "RESUME LENS", actions, activeView }: Props) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    const handleAuthExpired = () => router.replace("/");
    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    void refreshSession().then((session) => {
      if (session) setUser(session.user);
      else router.replace("/");
    });
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
  }, [router]);

  function logout() {
    setUser(null);
    void logoutSession();
    router.replace("/");
  }

  return (
    <div className="app-shell refined-workspace">
      <WorkspaceNav user={user} active={activeView} onLogout={logout} />
      <main className="workspace">
        <header className="topbar">
          <div>
            <span className="eyebrow">{eyebrow}</span>
            <h1>{title}</h1>
          </div>
          <div className="top-actions">
            <BackendStatus />
            {actions}
            <Link className="ghost" href="/">
              返回工作台
            </Link>
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}
