import { useCallback, useEffect, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import type { User } from "../lib/api";
import { bookmarkKey, parseBookmarks } from "../workspace-bookmarks";

export function useSavedJobs(user: User | null, setError: Dispatch<SetStateAction<string>>) {
  const [savedJobs, setSavedJobs] = useState<number[]>([]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try { setSavedJobs(user ? parseBookmarks(localStorage.getItem(bookmarkKey(user.id))) : []); }
      catch { setSavedJobs([]); }
    }, 0);
    return () => window.clearTimeout(timer);
  }, [user]);

  const toggleBookmark = useCallback((id: number) => {
    if (!user) return;
    setSavedJobs((current) => {
      const next = current.includes(id) ? current.filter((item) => item !== id) : [...current, id];
      try {
        localStorage.setItem(bookmarkKey(user.id), JSON.stringify(next));
        return next;
      } catch {
        setError("浏览器无法保存收藏，请检查存储权限后重试");
        return current;
      }
    });
  }, [setError, user]);

  return { savedJobs, setSavedJobs, toggleBookmark };
}
