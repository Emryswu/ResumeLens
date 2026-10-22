/**
 * Single source for the app's own URLs: the shell navigation, links into the workbench, and the old workbench
 * links that still point at "/". tests/workspace-nav.test.ts fails when another file writes such a URL by hand.
 */
export type HashView = "match" | "resumes" | "jobs" | "saved" | "history";
export type WorkspaceView = HashView | "assistant";
export type NavIconName = "match" | "resume" | "jobs" | "saved" | "history" | "assistant";

/** The assistant is the home page. */
export const HOME_PATH = "/";
/** The workbench: matching, resumes, jobs, bookmarks and history, switched by the hash. */
export const WORKSPACE_PATH = "/workspace";

export type NavItem = {
  view: WorkspaceView;
  label: string;
  href: string;
  icon: NavIconName;
};

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

export type WorkspaceSelection = { resumeId?: number; jobId?: number; analysisId?: number };

/** A workbench view, optionally with a resume, job or analysis preselected (the workbench reads these on load). */
export function workspaceHref(view: HashView = "match", selection: WorkspaceSelection = {}): string {
  return `${WORKSPACE_PATH}${selectionQuery(selection)}${hashForView(view)}`;
}

/** The workbench scrolled to a report. */
export function reportHref(selection: WorkspaceSelection & { analysisId: number }): string {
  return `${WORKSPACE_PATH}${selectionQuery(selection)}#result`;
}

function selectionQuery(selection: WorkspaceSelection): string {
  const params = new URLSearchParams();
  for (const key of ["resumeId", "jobId", "analysisId"] as const) {
    const value = selection[key];
    if (value != null && Number.isFinite(value)) params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

export const NAV_ITEMS: readonly NavItem[] = [
  { view: "assistant", label: "AI 助手", href: HOME_PATH, icon: "assistant" },
  { view: "match", label: "岗位匹配", href: workspaceHref("match"), icon: "match" },
  { view: "resumes", label: "我的简历", href: workspaceHref("resumes"), icon: "resume" },
  { view: "jobs", label: "职位库", href: workspaceHref("jobs"), icon: "jobs" },
  { view: "saved", label: "已保存", href: workspaceHref("saved"), icon: "saved" },
  { view: "history", label: "分析记录", href: workspaceHref("history"), icon: "history" },
];

/** Hashes the workbench used while it lived at "/"; "#result" is the report anchor. */
const LEGACY_HASHES = new Set(["#workflow", "#resumes", "#jobs", "#saved", "#history", "#result"]);
const SELECTION_KEYS = ["resumeId", "jobId", "analysisId"];

/**
 * Where an old workbench link on "/" belongs now, e.g. "/?analysisId=7#result" or "/#jobs" (bookmarks, links the
 * extension popup opened before it was updated). Null for the home page itself, which keeps everything else.
 */
export function legacyWorkspaceUrl(search: string, hash: string): string | null {
  const params = new URLSearchParams(search);
  const selects = SELECTION_KEYS.some((key) => params.has(key));
  if (!selects && !LEGACY_HASHES.has(hash)) return null;
  return `${WORKSPACE_PATH}${search}${hash}`;
}

export function navLabel(view: WorkspaceView): string {
  return NAV_ITEMS.find((item) => item.view === view)?.label ?? "";
}
