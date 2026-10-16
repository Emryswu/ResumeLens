"use client";

import { CircleAlert, CircleCheck, X } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { AppShell } from "../../components/AppShell";
import { useSessionUser } from "../../components/use-session-user";
import { apiRequest, visibleApiErrorMessage, type Resume } from "../../lib/api";

export default function ResumeDetailPage() {
  const { user, logout } = useSessionUser();
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = Number(params.id);
  const [resume, setResume] = useState<Resume | null>(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [form, setForm] = useState({
    title: "",
    candidateName: "",
    phone: "",
    email: "",
    rawText: "",
  });

  const load = useCallback(async () => {
    if (!Number.isFinite(id) || id <= 0) {
      setError("无效的简历 ID");
      return;
    }
    setBusy("load");
    setError("");
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
      setError(message);
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

  return (
    <AppShell
      title={resume ? resume.title : `简历 #${params.id}`}
      activeView="resumes"
      back={{ href: "/#resumes", label: "我的简历" }}
      user={user}
      onLogout={logout}
      actions={
        <>
          <button className="ghost" type="button" onClick={() => void load()} disabled={!!busy}>
            刷新
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
      {(error || notice) && (
        <div className={`toast ${error ? "error" : "success"}`}>
          {error ? <CircleAlert size={20} aria-hidden="true" /> : <CircleCheck size={20} aria-hidden="true" />}
          {error || notice}
          <button type="button" aria-label="关闭通知" onClick={() => { setError(""); setNotice(""); }}><X size={18} aria-hidden="true" /></button>
        </div>
      )}

      {busy === "load" && !resume && <p className="detail-muted">加载中…</p>}

      {resume && !editing && (
        <section className="detail-card">
          <div className="detail-meta">
            <div><span>ID</span><strong>#{resume.id}</strong></div>
            <div><span>姓名</span><strong>{resume.candidateName}</strong></div>
            <div><span>手机</span><strong>{resume.phone || "—"}</strong></div>
            <div><span>邮箱</span><strong>{resume.email || "—"}</strong></div>
            <div><span>文件</span><strong>{resume.originalFileName || "文本录入"}</strong></div>
            <div><span>更新</span><strong>{resume.updatedAt ? new Date(resume.updatedAt).toLocaleString("zh-CN") : "—"}</strong></div>
          </div>
          <h2>简历正文</h2>
          <pre className="detail-body">{resume.rawText || "（空）"}</pre>
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
