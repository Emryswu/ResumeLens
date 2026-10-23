/** Keep translations exhaustive against the extension protocol; collection-log.test.ts compares both tables. */
export const FINISH_MESSAGES = {
  NO_MORE_RESULTS: "列表没有更多结果了",
  MAX_JOBS: "已达到单次最多 45 条的上限",
  MAX_PAGES: "已读完要求的页数",
  NO_RESULTS: "这次搜索没有找到岗位",
  DAILY_LIMIT: "今天的采集页数已用完，读到的岗位已保留",
  DETAIL_READ: "已读取完整职位描述",
} as const;

export const STOP_MESSAGES = {
  PERMISSION_REQUIRED: "插件还没获得 BOSS 直聘的访问授权，请在插件弹窗里允许访问",
  BOSS_NOT_LOGGED_IN: "BOSS 直聘未登录，请先在这个浏览器里登录",
  CAPTCHA: "BOSS 直聘要求安全验证，已停止采集，请手动完成验证",
  STRUCTURE_CHANGED: "BOSS 搜索页结构发生变化，已停止采集，可能需要更新插件",
  PAGE_LOAD_TIMEOUT: "BOSS 搜索页加载超时，已停止采集",
  TAB_CLOSED: "采集用的标签页被关闭，已停止采集",
  UNEXPECTED_PAGE: "BOSS 搜索页跳转到了无法读取的页面，已停止采集",
  WORKER_RESTARTED: "插件后台在采集途中被浏览器重启，已保留读到的岗位",
  USER_CANCELLED: "已按要求停止采集",
  DAILY_LIMIT: "今天的采集页数已用完，明天再试",
  INTERNAL_ERROR: "插件内部错误，已停止采集",
  DETAIL_UNAVAILABLE: "完整 JD 没有加载出来或与目标岗位不一致，未将摘要标记为已核实",
} as const;

export type PageRead = { page: number; api: number; dom: number };
export type CollectionActivity = {
  phase?: string;
  pagesRead?: number;
  pageLog?: PageRead[];
  finishReason?: string | null;
  stopReason?: string | null;
};

export function collectionReason(activity: CollectionActivity): string | null {
  const message = activity.stopReason
    ? STOP_MESSAGES[activity.stopReason as keyof typeof STOP_MESSAGES]
    : FINISH_MESSAGES[activity.finishReason as keyof typeof FINISH_MESSAGES];
  if (!message) return null;
  return activity.pagesRead === 1 && activity.finishReason === "NO_MORE_RESULTS"
    ? `${message}；可能未登录 BOSS，只能看到第一页` : message;
}

export function collectionLog(activity: CollectionActivity): string[] {
  const lines: string[] = [];
  if (activity.phase || (activity.pagesRead ?? 0) > 0) lines.push("打开 BOSS 搜索页");
  for (const row of activity.pageLog ?? []) {
    lines.push(`第 ${row.page} 页读到 ${row.api + row.dom} 条（接口 ${row.api} 条 / 页面 ${row.dom} 条）`);
  }
  const reason = collectionReason(activity);
  if (reason) lines.push(`结束原因：${reason}`);
  return lines;
}
