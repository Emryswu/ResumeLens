/** Pure helpers behind the 我的简历 page, kept apart so they can be tested without rendering. */
import type { Resume } from "./lib/api";

/** What the backend's resume parser reads (ResumeController upload + Tika); kept in step with the file input. */
export const RESUME_EXTENSIONS = ["pdf", "doc", "docx", "txt", "md"] as const;
/** spring.servlet.multipart.max-file-size */
export const RESUME_MAX_BYTES = 20 * 1024 * 1024;
export const RESUME_ACCEPT = RESUME_EXTENSIONS.map((ext) => `.${ext}`).join(",");

function extensionOf(name?: string) {
  const match = name?.trim().toLowerCase().match(/\.([a-z0-9]+)$/);
  return match ? match[1] : "";
}

/** Why a picked or dropped file cannot be uploaded, or null when it can. Checked before the request, not after. */
export function resumeFileProblem(file: { name: string; size: number }): string | null {
  const ext = extensionOf(file.name);
  if (!(RESUME_EXTENSIONS as readonly string[]).includes(ext)) return "只支持 PDF、DOC、DOCX、TXT、MD 格式的简历";
  if (file.size === 0) return "这个文件是空的";
  if (file.size > RESUME_MAX_BYTES) return "文件超过 20MB，请压缩后再上传";
  return null;
}

export type FileKind = { label: string; tone: "pdf" | "word" | "text" | "paste" };

/** The small badge on a resume's file icon. A resume saved from pasted text has no file. */
export function resumeFileKind(resume: Pick<Resume, "fileExtension" | "originalFileName">): FileKind {
  const ext = (resume.fileExtension || extensionOf(resume.originalFileName)).replace(/^\./, "").toLowerCase();
  if (ext === "pdf") return { label: "PDF", tone: "pdf" };
  if (ext === "doc" || ext === "docx") return { label: ext.toUpperCase(), tone: "word" };
  if (ext === "txt" || ext === "md") return { label: ext.toUpperCase(), tone: "text" };
  return { label: "文本", tone: "paste" };
}

export function formatFileSize(bytes?: number): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes <= 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function stampOf(resume: Pick<Resume, "updatedAt" | "createdAt">) {
  const stamp = Date.parse(resume.updatedAt || resume.createdAt || "");
  return Number.isFinite(stamp) ? stamp : null;
}

/** "今天" / "昨天" / "N 天前" by calendar day in local time, falling back to the date after a month. */
export function relativeDay(stamp: number, now: number): string {
  const day = (value: number) => {
    const date = new Date(value);
    return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / 86400000;
  };
  const days = day(now) - day(stamp);
  if (days <= 0) return "今天";
  if (days === 1) return "昨天";
  if (days < 30) return `${days} 天前`;
  return new Date(stamp).toLocaleDateString("zh-CN");
}

/** The three numbers above the list. Only what the backend really knows: no parse progress, no target role. */
export function resumeStats(resumes: Pick<Resume, "updatedAt" | "createdAt" | "originalFileName">[], now: number) {
  const stamps = resumes.map(stampOf).filter((value): value is number => value != null);
  const latest = stamps.length ? Math.max(...stamps) : null;
  return {
    count: resumes.length,
    uploaded: resumes.filter((item) => item.originalFileName).length,
    latest: latest == null ? null : relativeDay(latest, now),
  };
}

export function resumeUpdatedLabel(resume: Pick<Resume, "updatedAt" | "createdAt">): string {
  const stamp = stampOf(resume);
  if (stamp == null) return "";
  const date = new Date(stamp);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
