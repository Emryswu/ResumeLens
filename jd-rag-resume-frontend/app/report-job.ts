/** Pick the JD actually used by a report. Never pair an old score with a changed live JD. */
import type { Analysis, Job, JobSnapshot } from "./lib/api";

export function jobContentForReport(
  analysis: Pick<Analysis, "jobSnapshot" | "jobInputChanged">,
  currentJob: Job | null,
): JobSnapshot | null {
  if (analysis.jobSnapshot) return analysis.jobSnapshot;
  if (analysis.jobInputChanged) return null;
  return currentJob;
}
