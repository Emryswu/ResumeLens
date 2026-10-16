"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AUTH_EXPIRED_EVENT, logoutSession, refreshSession, type User } from "../lib/api";

/**
 * Session guard for pages outside the home route: restores the session on mount
 * and sends the visitor back to sign-in when it is missing or expires.
 */
export function useSessionUser() {
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

  const logout = useCallback(() => {
    setUser(null);
    void logoutSession();
    router.replace("/");
  }, [router]);

  return { user, logout };
}
