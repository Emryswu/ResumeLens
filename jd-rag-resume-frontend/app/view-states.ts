import type { Analysis } from "./lib/api";

/** One request-backed block: still loading, failed with nothing to show, or ready. */
export type LoadState = "loading" | "error" | "ready";

/**
 * Data already on screen wins over a later failure: a failed refresh keeps the old content and reports the error
 * elsewhere, instead of replacing the page with an error block.
 */
export function loadState({ hasData, loading, error }: { hasData: boolean; loading: boolean; error: string }): LoadState {
  if (hasData) return "ready";
  if (error) return "error";
  return loading ? "loading" : "ready";
}

/** What the report panel of the match view shows. Exactly one of these, in priority order. */
export type ReportPanel =
  | "report"
  | "workspace-loading"
  | "analyzing"
  | "fetching"
  | "fetch-error"
  | "failed"
  | "no-resume"
  | "no-job"
  | "ready";

export function reportPanelState(input: {
  workspace: LoadState;
  analysis: Pick<Analysis, "status"> | null;
  analyzing: boolean;
  fetchingReport: boolean;
  /** The saved report for the current pair could not be read. */
  fetchError: boolean;
  resumeCount: number;
  jobCount: number;
}): ReportPanel {
  const { analysis, analyzing } = input;
  if (analysis?.status === "COMPLETED" && !analyzing) return "report";
  if (input.workspace === "loading") return "workspace-loading";
  if (analyzing || analysis?.status === "PENDING") return "analyzing";
  if (input.fetchingReport) return "fetching";
  if (input.fetchError) return "fetch-error";
  if (analysis?.status === "FAILED") return "failed";
  if (input.resumeCount === 0) return "no-resume";
  if (input.jobCount === 0) return "no-job";
  return "ready";
}
