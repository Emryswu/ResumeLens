"use client";

import { createContext, useContext, useEffect, useState, useSyncExternalStore } from "react";
import { ConversationSession, emptyConversationSnapshot } from "../conversation-session";
import { AUTH_EXPIRED_EVENT, AUTH_SESSION_CHANGED_EVENT, apiRequest, apiStream, getAuthSessionId, isAuthSessionCurrent } from "../lib/api";

const Context = createContext<ConversationSession | null>(null);

export function ConversationProvider({ children }: { children: React.ReactNode }) {
  const [session] = useState(() => new ConversationSession({ request: apiRequest, stream: apiStream, current: isAuthSessionCurrent }));
  useEffect(() => {
    window.addEventListener(AUTH_EXPIRED_EVENT, session.clear);
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, session.clear);
    return () => {
      window.removeEventListener(AUTH_EXPIRED_EVENT, session.clear);
      window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, session.clear);
      session.clear();
    };
  }, [session]);
  return <Context.Provider value={session}>{children}</Context.Provider>;
}

export function useConversation(userId: number) {
  const session = useContext(Context);
  if (!session) throw new Error("ConversationProvider is missing");
  const snapshot = useSyncExternalStore(session.subscribe, session.getSnapshot, () => emptyConversationSnapshot);
  const authSessionId = getAuthSessionId();
  useEffect(() => { void session.open(userId, authSessionId); }, [session, userId, authSessionId]);
  // Hide the old account synchronously, before the effect clears its cache.
  return { session, snapshot: snapshot.scope === `${userId}:${authSessionId}` ? snapshot : emptyConversationSnapshot };
}
