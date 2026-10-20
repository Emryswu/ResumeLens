/** Pure helpers behind the 分析记录 page, kept apart so they can be tested without rendering. */
import type { Analysis } from "./lib/api";

type Record = Pick<Analysis, "id" | "status" | "matchScore" | "createdAt" | "jobDescriptionId" | "jobTitle">;

export type TimeRange = "all" | "7d" | "30d";
export const TIME_RANGES: { value: TimeRange; label: string }[] = [
  { value: "all", label: "全部时间" },
  { value: "7d", label: "最近 7 天" },
  { value: "30d", label: "最近 30 天" },
];
const RANGE_DAYS: Record<Exclude<TimeRange, "all">, number> = { "7d": 7, "30d": 30 };

function stamp(item: Pick<Analysis, "createdAt">) {
  const value = Date.parse(item.createdAt);
  return Number.isFinite(value) ? value : null;
}

function completedScore(item: Pick<Analysis, "status" | "matchScore">) {
  if (item.status !== "COMPLETED" || item.matchScore == null) return null;
  const score = Number(item.matchScore);
  return Number.isFinite(score) ? score : null;
}

/** The records the table shows for the chosen time range and the 仅看已收藏岗位 switch. */
export function filterHistory<T extends Record>(items: T[], filter: { range: TimeRange; savedOnly: boolean; savedJobs: number[]; now: number }): T[] {
  const since = filter.range === "all" ? null : filter.now - RANGE_DAYS[filter.range] * 86400000;
  return items.filter((item) => {
    if (filter.savedOnly && !filter.savedJobs.includes(item.jobDescriptionId)) return false;
    if (since == null) return true;
    const time = stamp(item);
    return time != null && time >= since;
  });
}

/**
 * The four numbers above the table. The total comes from the server; the others can only use the records already
 * loaded, so `partial` says when they do not cover everything and the page has to say so.
 */
export function historyStats(items: Record[], total: number) {
  const scores = items.map(completedScore).filter((value): value is number => value != null);
  const latest = items.reduce<Record | null>((best, item) => {
    const time = stamp(item);
    if (time == null) return best;
    const bestTime = best ? stamp(best) : null;
    return bestTime == null || time > bestTime ? item : best;
  }, null);
  return {
    total: Math.max(total, items.length),
    best: scores.length ? Math.round(Math.max(...scores)) : null,
    strong: scores.filter((score) => score >= 80).length,
    latest,
    partial: items.length < total,
    loaded: items.length,
  };
}

export function analysisTime(createdAt: string): string {
  const value = Date.parse(createdAt);
  if (!Number.isFinite(value)) return "";
  const date = new Date(value);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function historyStatusLabel(status: Analysis["status"]): string {
  return status === "COMPLETED" ? "已完成" : status === "PENDING" ? "分析中" : "分析失败";
}
