/** Pure rules behind the matching workbench, kept apart so they can be tested without rendering. */
import type { AnalysisSummary, Job } from "./lib/api";

export type JobSource = "library" | "paste";

export type PasteJobForm = {
  title: string;
  companyName: string;
  location: string;
  description: string;
  requirements: string;
};

export const EMPTY_PASTE_JOB: PasteJobForm = { title: "", companyName: "", location: "", description: "", requirements: "" };

/** Same limits as the backend's JobDescriptionRequest, so a pasted job is rejected here rather than after the request. */
const PASTE_FIELDS: { key: keyof PasteJobForm; label: string; required: boolean; max: number }[] = [
  { key: "title", label: "职位名称", required: true, max: 160 },
  { key: "companyName", label: "公司名称", required: true, max: 120 },
  { key: "location", label: "工作地点", required: false, max: 80 },
  { key: "description", label: "职位描述", required: true, max: 20000 },
  { key: "requirements", label: "任职要求", required: false, max: 20000 },
];

export type PasteJobPayload = {
  title: string;
  companyName: string;
  location?: string;
  employmentType: string;
  description: string;
  requirements?: string;
};

/**
 * The body for POST /api/job-descriptions, or what still blocks it. Empty optional fields are left out instead of
 * being sent as "", so the saved job looks the same as one added from the job library.
 */
export function pasteJobPayload(form: PasteJobForm): { ok: true; payload: PasteJobPayload } | { ok: false; problems: string[] } {
  const problems: string[] = [];
  const value: Partial<Record<keyof PasteJobForm, string>> = {};
  for (const field of PASTE_FIELDS) {
    const text = form[field.key].trim();
    if (!text) {
      if (field.required) problems.push(`请填写${field.label}`);
      continue;
    }
    if (text.length > field.max) problems.push(`${field.label}最多 ${field.max} 个字`);
    value[field.key] = text;
  }
  if (problems.length > 0) return { ok: false, problems };
  return {
    ok: true,
    payload: {
      title: value.title!,
      companyName: value.companyName!,
      ...(value.location ? { location: value.location } : {}),
      employmentType: "全职",
      description: value.description!,
      ...(value.requirements ? { requirements: value.requirements } : {}),
    },
  };
}

/** Narrows the job picker by title, company or location; blank shows everything. */
export function filterJobs<T extends Pick<Job, "title" | "companyName" | "location">>(jobs: T[], query: string): T[] {
  const words = query.trim().toLocaleLowerCase("zh-CN").split(/\s+/).filter(Boolean);
  if (words.length === 0) return jobs;
  return jobs.filter((job) => {
    const haystack = [job.title, job.companyName, job.location].filter(Boolean).join(" ").toLocaleLowerCase("zh-CN");
    return words.every((word) => haystack.includes(word));
  });
}

export type WorkbenchAction =
  | { kind: "analyze" }
  /** The chosen pair already has a finished report: open it instead of paying for the same analysis again. */
  | { kind: "view-report"; analysisId: number }
  | { kind: "view-progress"; analysisId: number }
  | { kind: "blocked"; reason: string };

/** What the main button of the workbench does for the current choice. */
export function workbenchAction(input: {
  resumeId: number | "";
  source: JobSource;
  jobId: number | "";
  paste: PasteJobForm;
  /** Latest analysis of the chosen pair, once it is known. */
  latest?: Pick<AnalysisSummary, "id" | "status" | "resumeId" | "jobDescriptionId">;
}): WorkbenchAction {
  const { resumeId, source, jobId, paste, latest } = input;
  if (!resumeId) return { kind: "blocked", reason: "先选择一份简历" };
  if (source === "paste") {
    const checked = pasteJobPayload(paste);
    return checked.ok ? { kind: "analyze" } : { kind: "blocked", reason: checked.problems[0] };
  }
  if (!jobId) return { kind: "blocked", reason: "从职位库选一个岗位，或改为粘贴职位描述" };
  if (latest && latest.resumeId === resumeId && latest.jobDescriptionId === jobId) {
    if (latest.status === "COMPLETED") return { kind: "view-report", analysisId: latest.id };
    if (latest.status === "PENDING") return { kind: "view-progress", analysisId: latest.id };
  }
  return { kind: "analyze" };
}
