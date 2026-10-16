"use client";

import { Building2, Code, FolderKanban, Layers, Users, Wrench, type LucideIcon } from "lucide-react";
import type { EvidenceChunk } from "../../report-export";
import { CATEGORY_LABELS, chunkSimilarity, type ReportCategory, type ReportItem } from "../../report-items";

export type FindingKind = "strength" | "gap";

/** What the evidence drawer shows: one chunk, opened from one finding. */
export type OpenedEvidence = { key: string; finding: Pick<ReportItem, "title" | "detail">; chunk: number; kind: FindingKind | "requirement" };

const CATEGORY_ICONS: Record<ReportCategory, LucideIcon> = {
  TECH_STACK: Code,
  PROJECT: FolderKanban,
  ENGINEERING: Wrench,
  DOMAIN: Building2,
  COLLABORATION: Users,
  GENERAL: Layers,
};

export function EvidenceTag({ chunk, similarity, active, recorded, onOpen }: {
  chunk: number;
  /** Shown for strengths only; gaps never carry a number. */
  similarity?: number;
  active: boolean;
  recorded: boolean;
  onOpen: () => void;
}) {
  return (
    <button
      type="button"
      className="evidence-tag"
      aria-pressed={active}
      aria-controls="evidence-drawer"
      disabled={!recorded}
      title={recorded ? (similarity != null ? "检索相似度：文本相关程度，不等于能力评分" : undefined) : "这份报告没有保存该片段的原文"}
      onClick={onOpen}
    >
      片段 {chunk}
      {similarity != null && <span className="evidence-tag-sim">{similarity.toFixed(2)}</span>}
    </button>
  );
}

export function FindingList({ kind, items, evidence, opened, onOpen, empty }: {
  kind: FindingKind;
  items: ReportItem[];
  evidence: EvidenceChunk[];
  opened: OpenedEvidence | null;
  onOpen: (evidence: OpenedEvidence) => void;
  empty: string;
}) {
  if (items.length === 0) return <p className="finding-empty">{empty}</p>;
  return (
    <ul className={`finding-list ${kind}`}>
      {items.map((item, index) => {
        const Icon = CATEGORY_ICONS[item.category];
        return (
          <li key={`${index}-${item.title}`} className="finding">
            <span className={`finding-icon ${kind === "gap" ? "gap" : `cat-${item.category}`}`} aria-hidden="true"><Icon size={18} /></span>
            <div className="finding-body">
              <div className="finding-head">
                <strong>{item.title}</strong>
                <span className="finding-cat">{CATEGORY_LABELS[item.category]}</span>
                {item.source === "RULE" && <span className="finding-rule" title="由岗位要求中的硬技能规则检出，不是模型判断">规则检出</span>}
              </div>
              {item.detail && <p>{item.detail}</p>}
              {item.chunks.length > 0 && (
                <div className="finding-evidence">
                  <span>相关原文</span>
                  {item.chunks.map((chunk) => {
                    const key = `${kind}-${index}-${chunk}`;
                    const recorded = evidence.some((entry) => entry.index === chunk);
                    return (
                      <EvidenceTag
                        key={chunk}
                        chunk={chunk}
                        similarity={kind === "strength" ? chunkSimilarity(evidence, chunk) : undefined}
                        active={opened?.key === key}
                        recorded={recorded}
                        onOpen={() => onOpen({ key, finding: item, chunk, kind })}
                      />
                    );
                  })}
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
