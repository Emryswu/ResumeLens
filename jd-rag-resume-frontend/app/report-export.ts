/** Pure helpers for match-report Markdown / printable PDF export. */

export type ReportAnalysis = {
  id: number;
  resumeTitle: string;
  jobTitle: string;
  matchScore: number | null;
  status: string;
  summary?: string;
  retrievedContext?: string;
  strengths?: string;
  missingSkills?: string;
  improvementSuggestions?: string;
  interviewQuestions?: string;
  createdAt: string;
};

export type EvidenceChunk = {
  index: number;
  similarity: number;
  raw?: number;
  status: string;
  section: string;
  boost: string;
  content: string;
  kept: boolean;
};

export type RagMeta = {
  kept: number;
  filtered: number;
  avgSimilarity: number;
  minSimilarity: number;
  topK: number;
  hybrid: boolean;
  dualQuery: boolean;
};

export function asList(value?: string): string[] {
  if (!value?.trim()) return [];
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
  } catch {
    /* fall through */
  }
  return value
    .split(/\r?\n|[；;]/)
    // 只去列表标记：项目符号（- * •），以及「数字 + . ) 、」构成的序号。序号后不能紧跟数字，
    // 否则 "1.8s 慢查询" 会被当成序号 "1." 削成 "8s 慢查询"；裸数字开头（"5 年经验"）是正文。
    .map((item) => item.replace(/^\s*(?:[-*•]\s*)?(?:\d+[.)、](?!\d)\s*)?/, "").trim())
    .filter(Boolean);
}

export function parseRagMeta(value?: string): RagMeta | null {
  if (!value) return null;
  const line = value.split("\n").find((item) => item.startsWith("# rag-meta"));
  if (!line) return null;
  const num = (key: string, fallback = 0) => {
    const match = line.match(new RegExp(`${key}=([\\d.]+)`));
    return match ? Number(match[1]) : fallback;
  };
  return {
    kept: num("kept"),
    filtered: num("filtered"),
    avgSimilarity: num("avgSimilarity"),
    minSimilarity: num("minSimilarity", 0.55),
    topK: num("topK", 5),
    hybrid: line.includes("hybrid=true"),
    dualQuery: line.includes("dualQuery=true"),
  };
}

export function evidenceChunks(value?: string): EvidenceChunk[] {
  if (!value) return [];
  const pattern =
    /\[resume-chunk-(\d+)\s*\|\s*similarity=([\d.-]+)(?:\s*\|\s*raw=([\d.-]+))?(?:\s*\|\s*status=([^|\]]+))?(?:\s*\|\s*section=([^|\]]+))?(?:\s*\|\s*boost=([^|\]]+))?]\s*([\s\S]*?)(?=\n\s*\[resume-chunk-|\n\s*#\s|$)/g;
  return Array.from(value.matchAll(pattern)).map((match) => {
    const status = (match[4] || "kept").trim();
    return {
      index: Number(match[1]),
      similarity: Number(match[2]),
      raw: match[3] ? Number(match[3]) : undefined,
      status,
      section: (match[5] || "正文").trim(),
      boost: (match[6] || "-").trim(),
      content: match[7].trim(),
      kept: status.startsWith("kept"),
    };
  });
}

export function scoreLabel(score: number): string {
  if (score >= 85) return "高度匹配";
  if (score >= 70) return "值得尝试";
  return "需要优化";
}

function escapeHtml(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function listMarkdown(title: string, items: string[]): string {
  if (!items.length) return `## ${title}\n\n_（无）_\n`;
  return `## ${title}\n\n${items.map((item) => `- ${item}`).join("\n")}\n`;
}

export function buildReportMarkdown(analysis: ReportAnalysis): string {
  const score = Number(analysis.matchScore);
  const safeScore = Number.isFinite(score) ? score : 0;
  const strengths = asList(analysis.strengths);
  const missing = asList(analysis.missingSkills);
  const suggestions = asList(analysis.improvementSuggestions);
  const questions = asList(analysis.interviewQuestions);
  const evidence = evidenceChunks(analysis.retrievedContext);
  const meta = parseRagMeta(analysis.retrievedContext);
  const kept = evidence.filter((item) => item.kept);
  const created = analysis.createdAt
    ? new Date(analysis.createdAt).toLocaleString("zh-CN")
    : "";

  const lines: string[] = [
    `# 匹配分析报告 #${analysis.id}`,
    "",
    `> 由 ResumeLens · JD-RAG 生成 · ${created}`,
    "",
    "## 概览",
    "",
    `| 项 | 值 |`,
    `| --- | --- |`,
    `| 简历 | ${analysis.resumeTitle} |`,
    `| 职位 | ${analysis.jobTitle} |`,
    `| 状态 | ${analysis.status} |`,
    `| 匹配分 | ${Number.isFinite(score) ? safeScore.toFixed(2) : "—"} / 100（${scoreLabel(safeScore)}） |`,
    `| 有效证据 | ${meta?.kept ?? kept.length} |`,
    `| 已过滤 | ${meta?.filtered ?? Math.max(0, evidence.length - kept.length)} |`,
    `| 平均相似度 | ${(meta?.avgSimilarity ?? 0).toFixed(4)} |`,
    `| 阈值 / Top-K | ${(meta?.minSimilarity ?? 0.55).toFixed(2)} / ${meta?.topK ?? 5} |`,
    `| 检索 | ${meta?.hybrid === false ? "语义" : "关键词重排"}${meta?.dualQuery ? " · 双 Query" : ""} |`,
    "",
    "## 摘要",
    "",
    analysis.summary?.trim() || "_（无摘要）_",
    "",
    listMarkdown("核心优势", strengths),
    listMarkdown("能力缺口", missing),
    listMarkdown("优化建议", suggestions),
    listMarkdown("建议面试问题", questions),
    "## 检索证据链",
    "",
  ];

  if (!evidence.length) {
    lines.push(analysis.retrievedContext?.trim() || "_暂无检索证据_", "");
  } else {
    for (const chunk of evidence) {
      const flag = chunk.kept ? "进入 prompt" : chunk.status;
      lines.push(
        `### Chunk ${chunk.index} · ${chunk.section} · 相似度 ${(chunk.similarity * 100).toFixed(1)}% · ${flag}`,
        "",
        chunk.boost && chunk.boost !== "-" ? `Boost: \`${chunk.boost}\`` : "",
        chunk.boost && chunk.boost !== "-" ? "" : "",
        chunk.content,
        "",
      );
    }
  }

  lines.push("---", "", "_Embedding: Alibaba GTE (local CLS) · Analysis: OpenAI-compatible LLM_", "");
  return lines.filter((line, index, arr) => !(line === "" && arr[index - 1] === "")).join("\n");
}

export function buildReportPrintHtml(analysis: ReportAnalysis): string {
  const score = Number(analysis.matchScore);
  const safeScore = Number.isFinite(score) ? score : 0;
  const strengths = asList(analysis.strengths);
  const missing = asList(analysis.missingSkills);
  const suggestions = asList(analysis.improvementSuggestions);
  const questions = asList(analysis.interviewQuestions);
  const evidence = evidenceChunks(analysis.retrievedContext);
  const meta = parseRagMeta(analysis.retrievedContext);
  const kept = evidence.filter((item) => item.kept);
  const created = analysis.createdAt
    ? new Date(analysis.createdAt).toLocaleString("zh-CN")
    : "";

  const li = (items: string[]) =>
    items.length
      ? `<ul>${items.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>`
      : "<p class='muted'>（无）</p>";

  const evidenceHtml = evidence.length
    ? evidence
        .map((chunk) => {
          const flag = chunk.kept ? "进入 prompt" : escapeHtml(chunk.status);
          return `<article class="chunk ${chunk.kept ? "kept" : "drop"}">
  <header>Chunk ${chunk.index} · ${escapeHtml(chunk.section)} · ${(chunk.similarity * 100).toFixed(1)}% · ${flag}</header>
  ${chunk.boost && chunk.boost !== "-" ? `<div class="boost">boost: ${escapeHtml(chunk.boost)}</div>` : ""}
  <p>${escapeHtml(chunk.content)}</p>
</article>`;
        })
        .join("\n")
    : `<pre>${escapeHtml(analysis.retrievedContext || "暂无检索证据")}</pre>`;

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>匹配报告 #${analysis.id}</title>
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; padding: 32px 40px 48px; color: #112c32; font: 13px/1.6 "PingFang SC", "Microsoft YaHei", system-ui, sans-serif; }
    h1 { margin: 0 0 6px; font-family: Georgia, "Noto Serif SC", serif; font-size: 26px; font-weight: 600; }
    h2 { margin: 22px 0 10px; font-size: 15px; border-bottom: 1px solid #dfe4de; padding-bottom: 6px; }
    .meta { color: #6d7e7e; font-size: 12px; margin-bottom: 18px; }
    .score { display: inline-block; padding: 4px 10px; border-radius: 999px; background: #e5f6eb; color: #216a49; font-weight: 700; }
    table { border-collapse: collapse; width: 100%; margin: 8px 0 4px; }
    th, td { border: 1px solid #e4ebe6; padding: 7px 10px; text-align: left; font-size: 12px; }
    th { background: #f5f6f2; width: 28%; color: #5d7071; }
    ul { margin: 0; padding-left: 18px; }
    li { margin: 4px 0; }
    .muted { color: #8a9996; }
    .chunk { border: 1px solid #e4ebe6; border-radius: 8px; padding: 10px 12px; margin: 8px 0; page-break-inside: avoid; }
    .chunk.drop { opacity: .85; background: #fafafa; }
    .chunk header { font-weight: 700; font-size: 12px; margin-bottom: 6px; }
    .boost { color: #247353; font-size: 11px; margin-bottom: 4px; }
    .chunk p { margin: 0; white-space: pre-wrap; font-size: 12px; color: #3d5254; }
    pre { white-space: pre-wrap; background: #f7f5ef; padding: 12px; border-radius: 8px; font-size: 11px; }
    .hint { margin-top: 28px; color: #8a9996; font-size: 11px; }
    @media print {
      body { padding: 12mm 14mm; }
      .no-print { display: none !important; }
    }
  </style>
</head>
<body>
  <div class="no-print" style="margin-bottom:16px;padding:10px 12px;border:1px solid #cfe6d7;border-radius:8px;background:#f0faf3;font-size:12px;">
    在打印对话框中选择 <strong>「另存为 PDF」</strong> / <strong>Save as PDF</strong>，即可导出中文 PDF。
  </div>
  <h1>匹配分析报告 #${analysis.id}</h1>
  <div class="meta">ResumeLens · JD-RAG · ${escapeHtml(created)}</div>
  <p><span class="score">${Number.isFinite(score) ? safeScore.toFixed(1) : "—"} 分 · ${scoreLabel(safeScore)}</span></p>
  <h2>概览</h2>
  <table>
    <tr><th>简历</th><td>${escapeHtml(analysis.resumeTitle)}</td></tr>
    <tr><th>职位</th><td>${escapeHtml(analysis.jobTitle)}</td></tr>
    <tr><th>状态</th><td>${escapeHtml(analysis.status)}</td></tr>
    <tr><th>有效 / 过滤证据</th><td>${meta?.kept ?? kept.length} / ${meta?.filtered ?? Math.max(0, evidence.length - kept.length)}</td></tr>
    <tr><th>平均相似度</th><td>${(meta?.avgSimilarity ?? 0).toFixed(4)}</td></tr>
    <tr><th>阈值 · Top-K</th><td>${(meta?.minSimilarity ?? 0.55).toFixed(2)} · ${meta?.topK ?? 5}</td></tr>
  </table>
  <h2>摘要</h2>
  <p>${escapeHtml(analysis.summary?.trim() || "（无摘要）")}</p>
  <h2>核心优势</h2>
  ${li(strengths)}
  <h2>能力缺口</h2>
  ${li(missing)}
  <h2>优化建议</h2>
  ${li(suggestions)}
  <h2>建议面试问题</h2>
  ${li(questions)}
  <h2>检索证据链</h2>
  ${evidenceHtml}
  <p class="hint">Embedding: Alibaba GTE (local CLS) · Analysis: OpenAI-compatible LLM</p>
  <script>
    window.addEventListener('load', function () {
      setTimeout(function () { window.print(); }, 250);
    });
  </script>
</body>
</html>`;
}

export function reportFilename(analysis: ReportAnalysis, ext: "md" | "pdf"): string {
  const safe = (value: string) =>
    value.replace(/[\\/:*?"<>|]+/g, "_").replace(/\s+/g, "_").slice(0, 40);
  return `resume-match-${analysis.id}-${safe(analysis.resumeTitle)}-x-${safe(analysis.jobTitle)}.${ext}`;
}

export function downloadTextFile(filename: string, content: string, mime = "text/plain;charset=utf-8") {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function openPrintableReport(html: string) {
  const popup = window.open("", "_blank", "width=920,height=800");
  if (!popup) {
    throw new Error("浏览器拦截了弹窗，请允许后重试以导出 PDF");
  }
  popup.opener = null;
  popup.document.open();
  popup.document.write(html);
  popup.document.close();
}
