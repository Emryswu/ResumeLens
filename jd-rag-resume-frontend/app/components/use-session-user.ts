"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AUTH_EXPIRED_EVENT, logoutSession, refreshSession, type User } from "../lib/api";
import { HOME_PATH } from "../workspace-nav";

export type SessionStatus = "checking" | "signedIn" | "signedOut";

/**
 * Session guard: restores the session on mount. Pages other than the home page send a visitor without one back
 * to the home page, which signs them in; the home page passes {@code signInHere} and shows its own sign-in form.
 */
export function useSessionUser({ signInHere = false }: { signInHere?: boolean } = {}) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<SessionStatus>("checking");

  const signedOut = useCallback(() => {
    setUser(null);
    setStatus("signedOut");
    if (!signInHere) router.replace(HOME_PATH);
  }, [router, signInHere]);

  useEffect(() => {
    window.addEventListener(AUTH_EXPIRED_EVENT, signedOut);
    void refreshSession().then((session) => {
      if (!session) {
        signedOut();
        return;
      }
      setUser(session.user);
      setStatus("signedIn");
    });
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, signedOut);
  }, [signedOut]);

  const logout = useCallback(() => {
    void logoutSession();
    signedOut();
  }, [signedOut]);

  /** After the page's own sign-in form succeeded. */
  const signedIn = useCallback((next: User) => {
    setUser(next);
    setStatus("signedIn");
  }, []);

  return { user, status, logout, signedIn };
}
