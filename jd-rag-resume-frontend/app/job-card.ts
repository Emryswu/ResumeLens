/** Pure helpers behind JobCard, kept apart so they can be tested without rendering. */

export type Tone = "green" | "teal" | "amber" | "rose" | "violet" | "slate";
const TONES: Tone[] = ["green", "teal", "amber", "rose", "violet", "slate"];

/**
 * The square mark in front of a job: the company's first character on a colour picked from its name.
 * There are no logos on purpose: the jobs come from the user's own imports and real logos are trademarks.
 */
export function companyMark(name: string): { initial: string; tone: Tone } {
  const trimmed = name.trim();
  const first = [...trimmed][0] ?? "?";
  let hash = 0;
  for (const char of trimmed) hash = (hash * 31 + (char.codePointAt(0) ?? 0)) >>> 0;
  return { initial: first.toLocaleUpperCase("en"), tone: TONES[hash % TONES.length] };
}

/** Badge next to the title. Only a finished analysis earns one; semantic similarity is a first pass, not a verdict. */
export function jobFitLabel(score: number | null | undefined): { text: string; tone: "high" | "mid" | "low" } | null {
  if (score == null || !Number.isFinite(score)) return null;
  if (score >= 80) return { text: "高度匹配", tone: "high" };
  if (score >= 60) return { text: "较为匹配", tone: "mid" };
  return { text: "匹配一般", tone: "low" };
}

/** The meta line under the title: only the fields the job actually has, in a fixed order. */
export function jobMetaParts(job: { companyName: string; location?: string; employmentType?: string }): string[] {
  return [job.companyName, job.location, job.employmentType].map((part) => part?.trim()).filter((part): part is string => !!part);
}

/** What the ring on a job card shows. It follows the sort the user picked when both numbers exist. */
export function jobRing(input: {
  analysis?: { status: "PENDING" | "COMPLETED" | "FAILED"; matchScore: number | null };
  similarity?: number;
  prefer: "score" | "semantic";
}): { value: number | null; label: string; muted: boolean } {
  const { analysis, similarity, prefer } = input;
  const semantic = typeof similarity === "number" && Number.isFinite(similarity)
    ? { value: similarity * 100, label: "语义分", muted: true }
    : null;
  const score = analysis?.status === "COMPLETED" && analysis.matchScore != null ? Number(analysis.matchScore) : null;
  if (prefer === "semantic" && semantic) return semantic;
  if (score != null && Number.isFinite(score)) return { value: score, label: "匹配分", muted: false };
  if (analysis?.status === "PENDING") return { value: null, label: "分析中", muted: false };
  if (analysis?.status === "FAILED") return { value: null, label: "失败", muted: false };
  return semantic ?? { value: null, label: "未分析", muted: false };
}
