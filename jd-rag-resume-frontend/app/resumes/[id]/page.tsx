"use client";

import { CircleAlert, CircleCheck, FileText, RotateCw, X } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { AppShell } from "../../components/AppShell";
import { EmptyState, ErrorState, Skeleton } from "../../components/StateBlock";
import { useSessionUser } from "../../components/use-session-user";
import { apiRequest, visibleApiErrorMessage, type Resume } from "../../lib/api";
import { loadState } from "../../view-states";

export default function ResumeDetailPage() {
  const { user, logout } = useSessionUser();
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = Number(params.id);
  const [resume, setResume] = useState<Resume | null>(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [notice, setNotice] = useState("");
  const validId = Number.isFinite(id) && id > 0;
  const [form, setForm] = useState({
    title: "",
    candidateName: "",
    phone: "",
    email: "",
    rawText: "",
  });

  const load = useCallback(async () => {
    if (!Number.isFinite(id) || id <= 0) {
      setLoadError("无效的简历 ID");
      return;
    }
    setBusy("load");
    setError("");
    setLoadError("");
    try {
      const data = await apiRequest<Resume>(`/api/resumes/${id}`);
      setResume(data);
      setForm({
        title: data.title || "",
        candidateName: data.candidateName || "",
        phone: data.phone || "",
        email: data.email || "",
        rawText: data.rawText || "",
      });
    } catch (reason) {
      const message = visibleApiErrorMessage(reason, "加载简历失败");
      if (message == null) return;
      setLoadError(message);
    } finally {
      setBusy("");
    }
  }, [id]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy("save");
    setError("");
    try {
      const updated = await apiRequest<Resume>(`/api/resumes/${id}`, {
        method: "PUT",
        body: JSON.stringify(form),
      });
      setResume(updated);
      setEditing(false);
      setNotice("简历已更新，下次匹配会重建向量索引");
    } catch (reason) {
      const message = visibleApiErrorMessage(reason, "更新失败");
      if (message == null) return;
      setError(message);
    } finally {
      setBusy("");
    }
  }

  async function remove() {
    if (!window.confirm(`确认删除简历 #${id}？将清理上传文件与向量索引。`)) return;
    setBusy("delete");
    setError("");
    try {
      await apiRequest<void>(`/api/resumes/${id}`, { method: "DELETE" });
      router.replace("/");
    } catch (reason) {
      const message = visibleApiErrorMessage(reason, "删除失败");
      if (message == null) return;
      setError(message);
    } finally {
      setBusy("");
    }
  }

  // A failed refresh keeps the 简历 on screen and reports in the toast; with nothing loaded the page shows the retry.
  const view = loadState({ hasData: !!resume, loading: busy === "load" || !loadError, error: loadError });
  const toastError = error || (resume ? loadError : "");

  return (
    <AppShell
      title={resume ? resume.title : `简历 #${params.id}`}
      activeView="resumes"
      back={{ href: "/#resumes", label: "我的简历" }}
      user={user}
      onLogout={logout}
      actions={
        <>
          <button className="ghost" type="button" onClick={() => void load()} disabled={!!busy || !validId}>
            <RotateCw size={16} aria-hidden="true" />刷新
          </button>
          {resume && !editing && (
            <button className="ghost accent" type="button" onClick={() => setEditing(true)} disabled={!!busy}>
              编辑
            </button>
          )}
          {resume && (
            <button className="ghost danger" type="button" onClick={() => void remove()} disabled={!!busy}>
              删除
            </button>
          )}
        </>
      }
    >
      {(toastError || notice) && (
        <div className={`toast ${toastError ? "error" : "success"}`} role={toastError ? "alert" : "status"}>
          {toastError ? <CircleAlert size={20} aria-hidden="true" /> : <CircleCheck size={20} aria-hidden="true" />}
          {toastError || notice}
          <button type="button" aria-label="关闭通知" onClick={() => { setError(""); setLoadError(""); setNotice(""); }}><X size={18} aria-hidden="true" /></button>
        </div>
      )}

      {view === "loading" && <Skeleton className="detail-skeleton" label="正在读取简历" lines={6} />}
      {view === "error" && (
        <div className="state-panel detail-state">
          <ErrorState title="简历没有加载出来" text={loadError} onRetry={validId ? () => void load() : undefined}
            action={<Link className="ghost" href="/#resumes">返回我的简历</Link>} />
        </div>
      )}

      {resume && !editing && (
        <section className="detail-card">
          <div className="detail-meta">
            <div><span>ID</span><strong>#{resume.id}</strong></div>
            <div><span>姓名</span><strong title={resume.candidateName}>{resume.candidateName}</strong></div>
            <div><span>手机</span><strong title={resume.phone || "—"}>{resume.phone || "—"}</strong></div>
            <div><span>邮箱</span><strong title={resume.email || "—"}>{resume.email || "—"}</strong></div>
            <div><span>文件</span><strong title={resume.originalFileName || "文本录入"}>{resume.originalFileName || "文本录入"}</strong></div>
            <div><span>更新</span><strong>{resume.updatedAt ? new Date(resume.updatedAt).toLocaleString("zh-CN") : "—"}</strong></div>
          </div>
          <h2>简历正文</h2>
          {resume.rawText
            ? <pre className="detail-body">{resume.rawText}</pre>
            : <EmptyState compact icon={FileText} title="简历正文是空的" text="点「编辑」补充后，匹配时会用到这部分内容。"
              action={<button className="ghost" type="button" onClick={() => setEditing(true)} disabled={!!busy}>编辑</button>} />}
          <div className="form-actions">
            <Link className="secondary" href={`/?resumeId=${resume.id}`}>
              用此简历去匹配
            </Link>
          </div>
        </section>
      )}

      {resume && editing && (
        <form className="detail-card" onSubmit={save}>
          <div className="field-row">
            <label>简历标题<input required value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
            <label>姓名<input required value={form.candidateName} onChange={(e) => setForm({ ...form, candidateName: e.target.value })} /></label>
          </div>
          <div className="field-row">
            <label>手机<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
            <label>邮箱<input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
          </div>
          <label>简历文本<textarea required rows={16} value={form.rawText} onChange={(e) => setForm({ ...form, rawText: e.target.value })} /></label>
          <div className="form-actions">
            <button className="secondary" disabled={busy === "save"}>{busy === "save" ? "保存中…" : "保存修改"}</button>
            <button className="ghost" type="button" onClick={() => { setEditing(false); setForm({
              title: resume.title || "",
              candidateName: resume.candidateName || "",
              phone: resume.phone || "",
              email: resume.email || "",
              rawText: resume.rawText || "",
            }); }}>取消</button>
          </div>
        </form>
      )}
    </AppShell>
  );
}
