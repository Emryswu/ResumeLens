"use client";

import { ArrowRight, ChevronDown } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Analysis, Job, Resume } from "../lib/api";
import { apiRequest, getAuthSessionId, isAuthSessionCurrent, visibleApiErrorMessage } from "../lib/api";
import { asList, downloadTextFile, evidenceChunks, parseRagMeta } from "../report-export";
import { coverRequirements, coverageSummary } from "../requirement-coverage";
import { findingsFrom } from "../report-items";
import { resumeFormFrom } from "../resume-upload";
import { EvidenceDrawer } from "./report/EvidenceDrawer";
import { EvidenceTag, FindingList, type OpenedEvidence } from "./report/FindingList";
import { ReportHeader } from "./report/ReportHeader";
import { RetrievalDetails } from "./report/RetrievalDetails";

type Props = {
  analysis: Analysis;
  job?: Job;
  saved: boolean;
  disabled: boolean;
  onBookmark: () => void;
  onExportMarkdown: () => void;
  onExportPdf: () => void;
  onSaveCopy: (resume: Resume) => void;
};

/** A real report only: no fallback scores, invented evidence or generated rewrite claims. */
export function MatchReport({ analysis, job, saved, disabled, onBookmark, onExportMarkdown, onExportPdf, onSaveCopy }: Props) {
  const [tab, setTab] = useState<"evidence" | "edit" | "jd">("evidence");
  const [original, setOriginal] = useState<Resume | null>(null);
  const [reportJob, setReportJob] = useState<Job | null>(null);
  const [draft, setDraft] = useState("");
  const [draftError, setDraftError] = useState("");
  const [jobError, setJobError] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  // Separate counters on purpose: a shared one would let the JD retry re-run the
  // resume effect, whose setDraft() would silently overwrite unsaved edits.
  const [resumeRetry, setResumeRetry] = useState(0);
  const [jobRetry, setJobRetry] = useState(0);
  const [opened, setOpened] = useState<OpenedEvidence | null>(null);
  const closeEvidence = useCallback(() => setOpened(null), []);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const currentJob = job?.id === analysis.jobDescriptionId ? job : reportJob;
  const evidence = evidenceChunks(analysis.retrievedContext);
  const strengths = findingsFrom(analysis.strengthItems, asList(analysis.strengths));
  const gaps = findingsFrom(analysis.gapItems, asList(analysis.missingSkills));
  const suggestions = asList(analysis.improvementSuggestions);
  const questions = asList(analysis.interviewQuestions);
  const coverage = coverRequirements(currentJob?.requirements || "", evidence);
  const coverageCounts = coverageSummary(coverage);
  const meta = parseRagMeta(analysis.retrievedContext);

  useEffect(() => {
    let active = true;
    void apiRequest<Resume>(`/api/resumes/${analysis.resumeId}`).then((resume) => {
      if (!active) return;
      setOriginal(resume);
      setDraft(resume.rawText || "");
      setLoading(false);
      setDraftError("");
    }).catch((reason) => {
      if (!active) return;
      setDraftError(visibleApiErrorMessage(reason, "无法读取原简历，请重试") || "请重新登录");
      setLoading(false);
    });
    return () => { active = false; };
  }, [analysis.resumeId, resumeRetry]);

  useEffect(() => {
    if (job?.id === analysis.jobDescriptionId) return;
    let active = true;
    void apiRequest<Job>(`/api/job-descriptions/${analysis.jobDescriptionId}`).then((item) => {
      if (active) { setReportJob(item); setJobError(""); }
    }).catch(() => { if (active) setJobError("无法读取岗位原文，岗位可能已删除。"); });
    return () => { active = false; };
  }, [analysis.jobDescriptionId, job?.id, jobRetry]);

  async function saveCopy() {
    if (!original || !draft.trim() || saving) return;
    const session = getAuthSessionId();
    setSaving(true);
    setDraftError("");
    try {
      const copy = await apiRequest<Resume>("/api/resumes", {
        method: "POST",
        body: JSON.stringify({ ...resumeFormFrom(original), title: `${original.title.slice(0, 90)} · 修改版`, rawText: draft }),
      });
      if (mounted.current && isAuthSessionCurrent(session)) onSaveCopy(copy);
    } catch (reason) {
      if (mounted.current && isAuthSessionCurrent(session)) setDraftError(visibleApiErrorMessage(reason, "保存失败，草稿仍保留，可重试") || "请重新登录");
    } finally { if (mounted.current) setSaving(false); }
  }

  const score = analysis.matchScore != null && Number.isFinite(Number(analysis.matchScore)) ? Number(analysis.matchScore) : null;
  const jobMeta = [currentJob?.companyName, currentJob?.location].filter(Boolean).join(" · ");

  return <>
    <ReportHeader reportId={analysis.id} score={score} resumeTitle={analysis.resumeTitle} jobTitle={analysis.jobTitle}
      jobMeta={jobMeta} summary={analysis.summary} saved={saved} onBookmark={onBookmark} />
    <div className="review-tabs" role="tablist" aria-label="报告内容">{([
      ["evidence", "匹配依据"], ["edit", "修改简历"], ["jd", "岗位要求"],
    ] as const).map(([value, label], index, all) => <button key={value} id={`review-tab-${value}`} type="button" role="tab" aria-selected={tab === value} aria-controls="review-panel" onClick={() => setTab(value)} onKeyDown={(event) => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === "Home" ? 0 : event.key === "End" ? 2 : (index + (event.key === "ArrowRight" ? 1 : 2)) % 3;
      setTab(all[next][0]);
      document.getElementById(`review-tab-${all[next][0]}`)?.focus();
    }}>{label}</button>)}</div>
    <div className="review-body" id="review-panel" role="tabpanel" aria-labelledby={`review-tab-${tab}`}>
      {tab === "evidence" && <>
        <section className="finding-section">
          <h3>你的经历与岗位要求</h3>
          <p className="review-caveat">每一条都引用了你简历中的片段；点「片段」查看相关原文。</p>
          <FindingList kind="strength" items={strengths} evidence={evidence} opened={opened} onOpen={setOpened}
            empty="这份报告没有列出有简历片段支撑的优势。" />
        </section>
        <section className="finding-section">
          <h3>简历中还没体现的要求</h3>
          <p className="review-caveat">简历里没写，不代表你不具备这项能力；补充前请确认是你真实做过的。</p>
          <FindingList kind="gap" items={gaps} evidence={evidence} opened={opened} onOpen={setOpened}
            empty="这份报告没有列出明确缺口，仍建议对照岗位要求逐条核对。" />
        </section>
        {coverage.length > 0 && <details className="review-evidence"><summary><h3>逐条核对岗位要求</h3><span className="review-muted">{coverageCounts.covered} / {coverageCounts.total} 条词面命中</span></summary><p className="review-caveat">只按岗位要求与保留片段的词面重叠提示，不是模型判断；同义表述可能没有命中。</p><ol className="requirement-list">{coverage.map(row => <li key={row.no}><span>{row.text}</span><span className="finding-evidence">{row.covered ? row.chunks.map(index => {
          const key = `requirement-${row.no}-${index}`;
          return <EvidenceTag key={index} chunk={index} active={opened?.key === key} recorded={evidence.some(item => item.index === index)}
            onOpen={() => setOpened({ key, finding: { title: row.text, detail: "" }, chunk: index, kind: "requirement" })} />;
        }) : "未找到词面相同的片段"}</span></li>)}</ol></details>}
        <RetrievalDetails meta={meta} evidence={evidence} raw={analysis.retrievedContext} />
      </>}
      {tab === "edit" && <>
        <h3>让经历，说得更具体。</h3><p className="review-caveat">以当前简历原文起稿，参考分析建议自行修改。尚未自动生成改写内容。</p>
        {suggestions.length > 0 && <ul className="rewrite-suggestions">{suggestions.map((text, index) => <li key={index}>{text}</li>)}</ul>}
        {loading ? <p role="status">正在读取原简历…</p> : original ? <div className="rewrite-compare"><section><h3>当前简历原文</h3><p className="review-caveat">历史报告可能对应较早版本，请核对修改时间。</p><p className="source-text">{original.rawText || "原文为空"}</p></section><section><label htmlFor="resume-draft">修改草稿 · 可直接编辑</label><textarea id="resume-draft" rows={16} value={draft} onChange={event => setDraft(event.target.value)} disabled={saving} /></section></div> : null}
        <p className="review-caveat warning">仅补充亲自完成、能够验证的经历和数字。切换报告前请另存或下载草稿。</p>
        {draftError && <div className="message error" role="alert">{draftError}{!original && <button type="button" className="ghost" onClick={() => { setLoading(true); setResumeRetry(value => value + 1); }}>重试读取</button>}</div>}
        <div className="form-actions"><button type="button" className="primary" disabled={disabled || saving || !original || !draft.trim() || draft === original.rawText} onClick={() => void saveCopy()}>{saving ? "保存中…" : "另存为新简历"}</button><button type="button" className="ghost" disabled={!draft.trim()} onClick={() => downloadTextFile(`resume-${analysis.resumeId}-draft.txt`, draft, "text/plain;charset=utf-8")}>下载草稿</button></div>
        {questions.length > 0 && <details className="review-evidence interview-questions"><summary><h3>面试准备问题</h3><ChevronDown size={18} aria-hidden="true" /></summary><ol>{questions.map((text, index) => <li key={index}>{text}</li>)}</ol></details>}
      </>}
      {tab === "jd" && <>{currentJob ? <><h3>岗位职责</h3><p className="source-text">{currentJob.description || "未提供岗位职责"}</p><h3>任职要求</h3><p className="source-text">{currentJob.requirements || "未提供任职要求"}</p></> : <p role="status">{jobError || "正在读取岗位要求…"}{jobError && <button className="ghost" onClick={() => setJobRetry(value => value + 1)}>重试</button>}</p>}</>}
    </div>
    <footer className="review-footer"><div><span className="review-muted">{new Date(analysis.createdAt).toLocaleString("zh-CN")}</span><div className="review-export"><button type="button" className="text-action" onClick={onExportMarkdown}>导出 Markdown</button><button type="button" className="text-action" onClick={onExportPdf}>导出 PDF</button></div></div>{tab !== "edit" && <button type="button" className="primary" onClick={() => setTab("edit")}>对照原文修改<ArrowRight size={16} aria-hidden="true" /></button>}</footer>
    <EvidenceDrawer opened={opened} evidence={evidence} onClose={closeEvidence} />
  </>;
}
