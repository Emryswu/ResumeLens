export type CollectorMode = "auto" | "chrome" | "patchright";
export const COLLECTOR_OPTIONS = [
  { value: "auto", label: "采集方式：自动", description: "优先用我的 Chrome，插件连接失败时使用独立浏览器" },
  { value: "chrome", label: "采集方式：我的 Chrome", description: "只使用安装了 ResumeLens 插件的 Chrome" },
  { value: "patchright", label: "采集方式：独立浏览器", description: "使用独立窗口；首次登录 BOSS 后会记住登录状态" },
] as const;

export const collectorStorageKey = (userId: number) => `resumelens-collector-modes:${userId}`;

/** localStorage is an external boundary; ignore stale or malformed selections. */
export function readCollectorModes(raw: string | null): Record<number, CollectorMode> {
  if (!raw) return {};
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value)) return {};
    return Object.fromEntries(Object.entries(value).filter(([id, mode]) => /^\d+$/.test(id)
      && ["auto", "chrome", "patchright"].includes(String(mode)))) as Record<number, CollectorMode>;
  } catch { return {}; }
}
