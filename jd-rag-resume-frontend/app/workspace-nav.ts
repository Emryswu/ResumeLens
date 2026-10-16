/** Single source for the shell navigation: every page renders these items. */
export type HashView = "match" | "resumes" | "jobs" | "saved" | "history";
export type WorkspaceView = HashView | "assistant";
export type NavIconName = "match" | "resume" | "jobs" | "saved" | "history" | "assistant";

export type NavItem = {
  view: WorkspaceView;
  label: string;
  href: string;
  icon: NavIconName;
};

export const NAV_ITEMS: readonly NavItem[] = [
  { view: "match", label: "岗位匹配", href: "/#workflow", icon: "match" },
  { view: "resumes", label: "我的简历", href: "/#resumes", icon: "resume" },
  { view: "jobs", label: "职位库", href: "/#jobs", icon: "jobs" },
  { view: "saved", label: "已保存", href: "/#saved", icon: "saved" },
  { view: "history", label: "分析记录", href: "/#history", icon: "history" },
  // A separate route, not a hash view, so the home page never intercepts it.
  { view: "assistant", label: "AI 助手", href: "/assistant", icon: "assistant" },
];

const HASH_VIEWS: readonly HashView[] = ["resumes", "jobs", "saved", "history"];

export function isHashView(view: WorkspaceView): view is HashView {
  return view !== "assistant";
}

/** "#jobs" -> "jobs"; anything unknown (including "#workflow") falls back to the match view. */
export function viewFromHash(hash: string): HashView {
  const key = hash.replace(/^#/, "");
  return (HASH_VIEWS as readonly string[]).includes(key) ? key as HashView : "match";
}

export function hashForView(view: HashView): string {
  return `#${view === "match" ? "workflow" : view}`;
}

export function navLabel(view: WorkspaceView): string {
  return NAV_ITEMS.find((item) => item.view === view)?.label ?? "";
}
