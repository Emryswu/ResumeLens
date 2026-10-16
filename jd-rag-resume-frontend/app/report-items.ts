import type { EvidenceChunk } from "./report-export";

/** Stored and sent as English codes; the Chinese names exist only here, for display. */
export type ReportCategory = "TECH_STACK" | "PROJECT" | "ENGINEERING" | "DOMAIN" | "COLLABORATION" | "GENERAL";

export type ReportItem = {
  title: string;
  detail: string;
  chunks: number[];
  category: ReportCategory;
  source: "MODEL" | "RULE";
};

export const CATEGORY_LABELS: Record<ReportCategory, string> = {
  TECH_STACK: "技术栈",
  PROJECT: "项目经验",
  ENGINEERING: "工程实践",
  DOMAIN: "业务领域",
  COLLABORATION: "协作沟通",
  GENERAL: "综合",
};

export function normalizeCategory(value: unknown): ReportCategory {
  return typeof value === "string" && value in CATEGORY_LABELS ? value as ReportCategory : "GENERAL";
}

const CITATION = /\[chunk-(\d+)]/g;
const RULE_PREFIX = /^规则校验缺失[:：]\s*/;

/**
 * One list of findings for the report, whatever the report's age: structured items when the server sent them,
 * otherwise the plain-text lines (asList of the text field) of a report written before structured output, with
 * their [chunk-N] tokens moved out of the text into `chunks`.
 */
export function findingsFrom(items: unknown, legacyLines: string[]): ReportItem[] {
  if (Array.isArray(items)) {
    return items.flatMap((raw) => {
      if (!raw || typeof raw !== "object") return [];
      const item = raw as Record<string, unknown>;
      const title = typeof item.title === "string" ? item.title.trim() : "";
      const detail = typeof item.detail === "string" ? item.detail.trim() : "";
      if (!title && !detail) return [];
      const chunks = Array.isArray(item.chunks)
        ? item.chunks.filter((chunk): chunk is number => Number.isInteger(chunk) && (chunk as number) >= 0)
        : [];
      return [{
        title: title || detail,
        detail: title ? detail : "",
        chunks: [...new Set(chunks)],
        category: normalizeCategory(item.category),
        source: item.source === "RULE" ? "RULE" : "MODEL",
      }];
    });
  }
  return legacyLines.flatMap((line): ReportItem[] => {
    // Old reports joined the hard-skill rule gaps into one line: "规则校验缺失：Java、MySQL".
    if (RULE_PREFIX.test(line)) {
      return line.replace(RULE_PREFIX, "").split(/[、,，]/).map((skill) => skill.trim()).filter(Boolean)
        .map((skill) => ({ title: skill, detail: "", chunks: [], category: "TECH_STACK", source: "RULE" }));
    }
    const chunks = [...new Set(Array.from(line.matchAll(CITATION), (match) => Number(match[1])))];
    const title = line.replace(CITATION, "").replace(/\s{2,}/g, " ").trim();
    return title ? [{ title, detail: "", chunks, category: "GENERAL", source: "MODEL" }] : [];
  });
}

/** The retrieval similarity of a cited chunk, or undefined when the report did not record that chunk. */
export function chunkSimilarity(evidence: EvidenceChunk[], index: number): number | undefined {
  const chunk = evidence.find((item) => item.index === index);
  return chunk && Number.isFinite(chunk.similarity) ? chunk.similarity : undefined;
}
