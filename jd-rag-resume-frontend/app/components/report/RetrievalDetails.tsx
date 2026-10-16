"use client";

import { useState } from "react";
import type { EvidenceChunk, RagMeta } from "../../report-export";

/** Retrieval parameters and every chunk, folded away by default: useful for checking, not for reading the report. */
export function RetrievalDetails({ meta, evidence, raw }: { meta: RagMeta | null; evidence: EvidenceChunk[]; raw?: string }) {
  const [showFiltered, setShowFiltered] = useState(false);
  const kept = evidence.filter((item) => item.kept);
  const shown = evidence.filter((item) => item.kept || showFiltered);
  return (
    <details className="retrieval-details">
      <summary>检索详情</summary>
      <dl className="retrieval-params">
        <div><dt>保留片段</dt><dd>{meta?.kept ?? kept.length}</dd></div>
        <div><dt>被过滤</dt><dd>{meta?.filtered ?? evidence.length - kept.length}</dd></div>
        <div><dt>相似度阈值</dt><dd>{meta?.minSimilarity ?? "未记录"}</dd></div>
        <div><dt>Top-K</dt><dd>{meta?.topK ?? "未记录"}</dd></div>
        <div><dt>平均相似度</dt><dd>{meta ? meta.avgSimilarity.toFixed(2) : "未记录"}</dd></div>
      </dl>
      <p className="review-caveat">相似度表示文本相关程度，不等于能力评分。</p>
      {evidence.some((item) => !item.kept) && (
        <label className="inline-check"><input type="checkbox" checked={showFiltered} onChange={(event) => setShowFiltered(event.target.checked)} />同时查看被过滤的片段</label>
      )}
      {shown.map((item) => (
        <details className="review-evidence source-chunk" id={`chunk-${item.index}`} key={`${item.index}-${item.status}`}>
          <summary><span>片段 {item.index} · {item.section}</span><span className="review-muted">{item.kept ? "已保留" : "已过滤"} · {item.similarity.toFixed(2)}</span></summary>
          <p className="source-text">{item.content}</p>
        </details>
      ))}
      {evidence.length === 0 && <p className="review-caveat">这份报告没有可解析的原文片段。</p>}
      <details className="review-technical"><summary>原始检索记录</summary><pre>{raw || "暂无检索记录"}</pre></details>
    </details>
  );
}
