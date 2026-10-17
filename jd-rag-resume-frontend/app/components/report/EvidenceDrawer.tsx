"use client";

import { highlightSegments } from "../../evidence-highlight";
import type { EvidenceChunk } from "../../report-export";
import { SideDrawer } from "../SideDrawer";
import type { OpenedEvidence } from "./FindingList";

/**
 * Non-modal side panel with the resume text of one cited chunk. Marks are a word-level heuristic that helps the
 * reader find the related words; the copy never claims the chunk proves the finding.
 */
export function EvidenceDrawer({ opened, evidence, onClose }: {
  opened: OpenedEvidence | null;
  evidence: EvidenceChunk[];
  onClose: () => void;
}) {
  const chunk = opened ? evidence.find((item) => item.index === opened.chunk) : undefined;
  if (!opened) return null;
  const segments = chunk ? highlightSegments(chunk.content, opened.finding) : [];
  const marked = segments.some((segment) => segment.mark);
  return (
    <SideDrawer id="evidence-drawer" title="相关原文片段" subtitle={`片段 ${opened.chunk}${chunk ? ` · ${chunk.section}` : ""}`}
      closeLabel="关闭原文片段" focusKey={opened} onClose={onClose}>
      <p className="evidence-drawer-for">
        <span>{opened.kind === "requirement" ? "岗位要求" : opened.kind === "gap" ? "缺口" : "优势"}</span>
        {opened.finding.title}
      </p>
      {chunk ? (
        <>
          {/* The caveat comes before the text so it is read first, not after scrolling past a long chunk. */}
          <p className="evidence-drawer-note">
            {marked ? "高亮的是与这一条字面相同的词，只用来帮你定位，不代表这段原文一定支撑上面的结论。" : "这段原文里没有与这一条字面相同的词，请自行对照。"}
            {opened.kind === "strength" && ` 检索相似度 ${chunk.similarity.toFixed(2)}，表示文本相关程度，不等于能力评分。`}
          </p>
          {/* No literal overlap: outline the whole chunk instead of marking nothing. */}
          <p className={`source-text evidence-drawer-text${marked ? "" : " unmatched"}`}>
            {segments.map((segment, index) => segment.mark ? <mark key={index}>{segment.text}</mark> : segment.text)}
          </p>
        </>
      ) : (
        <p className="evidence-drawer-note">这份报告没有保存片段 {opened.chunk} 的原文。</p>
      )}
    </SideDrawer>
  );
}
