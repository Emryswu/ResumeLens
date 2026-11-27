import { useCallback, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { filterHistory, type TimeRange } from "../history-view";
import { apiRequest, type Analysis, type PageData } from "../lib/api";
import { HISTORY_PAGE_SIZE } from "./workspace-helpers";

type UseHistoryLibraryParams = {
  savedJobs: number[];
  pageOpenedAt: number;
  setError: Dispatch<SetStateAction<string>>;
  reportRequestError: (reason: unknown, fallback: string) => void;
};

export function useHistoryLibrary({
  savedJobs,
  pageOpenedAt,
  setError,
  reportRequestError,
}: UseHistoryLibraryParams) {
  const [history, setHistory] = useState<Analysis[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyRange, setHistoryRange] = useState<TimeRange>("all");
  const [historySavedOnly, setHistorySavedOnly] = useState(false);
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false);

  const applyInitialHistory = useCallback((page: PageData<Analysis>) => {
    setHistory(page.content);
    setHistoryTotal(page.totalElements);
    setHistoryPage(0);
  }, []);

  const resetHistory = useCallback(() => {
    setHistory([]);
    setHistoryTotal(0);
    setHistoryPage(0);
    setHistoryRange("all");
    setHistorySavedOnly(false);
  }, []);

  const loadMoreHistory = useCallback(async () => {
    if (historyLoadingMore || history.length >= historyTotal) return;
    setHistoryLoadingMore(true);
    setError("");
    try {
      const nextPage = historyPage + 1;
      const page = await apiRequest<PageData<Analysis>>(
        `/api/analysis-histories?page=${nextPage}&size=${HISTORY_PAGE_SIZE}`,
      );
      setHistory((items) => {
        const seen = new Set(items.map((item) => item.id));
        return [...items, ...page.content.filter((item) => !seen.has(item.id))];
      });
      setHistoryTotal(page.totalElements);
      setHistoryPage(nextPage);
    } catch (reason) {
      reportRequestError(reason, "无法加载更多分析记录");
    } finally {
      setHistoryLoadingMore(false);
    }
  }, [history.length, historyLoadingMore, historyPage, historyTotal, reportRequestError, setError]);

  const effectiveHistoryTotal = Math.max(historyTotal, history.length);
  const visibleHistory = filterHistory(history, {
    range: historyRange,
    savedOnly: historySavedOnly,
    savedJobs,
    now: pageOpenedAt,
  });
  const hasMoreHistory = history.length < effectiveHistoryTotal;

  return {
    history,
    setHistory,
    historyRange,
    setHistoryRange,
    historySavedOnly,
    setHistorySavedOnly,
    historyLoadingMore,
    effectiveHistoryTotal,
    visibleHistory,
    hasMoreHistory,
    applyInitialHistory,
    resetHistory,
    loadMoreHistory,
  };
}
