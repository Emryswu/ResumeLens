"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, visibleApiErrorMessage } from "../../lib/api";

type Catalog = {
  enabled: boolean;
  connected: boolean;
  models: { slug: string; name: string }[];
};

const EMPTY: Catalog = { enabled: false, connected: false, models: [] };

export function useLocalChatGptModels(userId: number) {
  const key = `resumelens-chatgpt-model:${userId}`;
  const [catalog, setCatalog] = useState<Catalog>(EMPTY);
  const [selected, setSelected] = useState(() =>
    typeof window === "undefined" ? "" : window.localStorage.getItem(key) ?? "");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const request = useRef(0);
  const refresh = useCallback(async () => {
    const id = ++request.current;
    try {
      const next = await apiRequest<Catalog>("/api/ai/chatgpt/models");
      if (request.current === id) { setCatalog(next); setError(""); }
    } catch (reason) {
      if (request.current === id) setError(visibleApiErrorMessage(reason, "读取 ChatGPT 模型失败") ?? "");
    }
  }, []);
  useEffect(() => {
    if (process.env.NEXT_PUBLIC_LOCAL_CHATGPT_SETTINGS !== "true" && !selected) return;
    void refresh();
    const focus = () => void refresh();
    window.addEventListener("focus", focus);
    return () => {
      request.current++;
      window.removeEventListener("focus", focus);
    };
  }, [refresh, selected]);

  async function choose(slug: string) {
    if (saving) return;
    setSaving(true);
    setError("");
    try {
      if (slug) {
        await apiRequest<void>("/api/ai/chatgpt/model", {
          method: "PUT",
          body: JSON.stringify({ slug }),
        });
      }
      // Preserve a chosen subscription model across reloads, even if temporarily
      // unavailable. Never silently revert to the separately billed API.
      window.localStorage.setItem(key, slug);
      setSelected(slug);
    } catch (reason) {
      setError(visibleApiErrorMessage(reason, "切换模型失败") ?? "");
    } finally {
      setSaving(false);
    }
  }

  return { ...catalog, selected, error, saving, refresh, choose };
}
