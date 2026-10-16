"use client";

import { CircleAlert, CircleCheck, X } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { AppShell } from "../../components/AppShell";
import { useSessionUser } from "../../components/use-session-user";
import { apiRequest, visibleApiErrorMessage, type Job } from "../../lib/api";

export default function JobDetailPage() {
  const { user, logout } = useSessionUser();
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = Number(params.id);
  const [job, setJob] = useState<Job | null>(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [form, setForm] = useState({
    title: "",
    companyName: "",
    location: "",
    employmentType: "全职",
    description: "",
    requirements: "",
  });

  const load = useCallback(async () => {
    if (!Number.isFinite(id) || id <= 0) {
      setError("无效的职位 ID");
      return;
    }
    setBusy("load");
    setError("");
    try {
      const data = await apiRequest<Job>(`/api/job-descriptions/${id}`);
      setJob(data);
      setForm({
        title: data.title || "",
        companyName: data.companyName || "",
        location: data.location || "",
        employmentType: data.employmentType || "全职",
        description: data.description || "",
        requirements: data.requirements || "",
      });
    } catch (reason) {
      const message = visibleApiErrorMessage(reason, "加载职位失败");
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
      const updated = await apiRequest<Job>(`/api/job-descriptions/${id}`, {
        method: "PUT",
        body: JSON.stringify(form),
      });
      setJob(updated);
      setEditing(false);
      setNotice("职位已更新");
    } catch (reason) {
      const message = visibleApiErrorMessage(reason, "更新失败");
      if (message == null) return;
      setError(message);
    } finally {
      setBusy("");
    }
  }

  async function remove() {
    if (!window.confirm(`确认删除职位 #${id}？`)) return;
    setBusy("delete");
    setError("");
    try {
      await apiRequest<void>(`/api/job-descriptions/${id}`, { method: "DELETE" });
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
      title={job ? job.title : `职位 #${params.id}`}
      activeView="jobs"
      back={{ href: "/#jobs", label: "职位库" }}
      user={user}
      onLogout={logout}
      actions={
        <>
          <button className="ghost" type="button" onClick={() => void load()} disabled={!!busy}>
            刷新
          </button>
          {job && !editing && (
            <button className="ghost accent" type="button" onClick={() => setEditing(true)} disabled={!!busy}>
              编辑
            </button>
          )}
          {job && (
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

      {busy === "load" && !job && <p className="detail-muted">加载中…</p>}

      {job && !editing && (
        <section className="detail-card">
          <div className="detail-meta">
            <div><span>ID</span><strong>#{job.id}</strong></div>
            <div><span>公司</span><strong>{job.companyName}</strong></div>
            <div><span>地点</span><strong>{job.location || "—"}</strong></div>
            <div><span>类型</span><strong>{job.employmentType || "—"}</strong></div>
            <div><span>更新</span><strong>{job.updatedAt ? new Date(job.updatedAt).toLocaleString("zh-CN") : "—"}</strong></div>
          </div>
          <h2>岗位描述</h2>
          <pre className="detail-body">{job.description || "（空）"}</pre>
          <h2>任职要求</h2>
          <pre className="detail-body">{job.requirements || "（空）"}</pre>
          <div className="form-actions">
            <Link className="secondary" href={`/?jobId=${job.id}`}>
              用此职位去匹配
            </Link>
          </div>
        </section>
      )}

      {job && editing && (
        <form className="detail-card" onSubmit={save}>
          <div className="field-row">
            <label>职位名称<input required value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
            <label>公司名称<input required value={form.companyName} onChange={(e) => setForm({ ...form, companyName: e.target.value })} /></label>
          </div>
          <div className="field-row">
            <label>工作地点<input value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} /></label>
            <label>用工类型<input value={form.employmentType} onChange={(e) => setForm({ ...form, employmentType: e.target.value })} /></label>
          </div>
          <label>岗位描述<textarea required rows={6} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
          <label>任职要求<textarea rows={6} value={form.requirements} onChange={(e) => setForm({ ...form, requirements: e.target.value })} /></label>
          <div className="form-actions">
            <button className="secondary" disabled={busy === "save"}>{busy === "save" ? "保存中…" : "保存修改"}</button>
            <button className="ghost" type="button" onClick={() => {
              setEditing(false);
              setForm({
                title: job.title || "",
                companyName: job.companyName || "",
                location: job.location || "",
                employmentType: job.employmentType || "全职",
                description: job.description || "",
                requirements: job.requirements || "",
              });
            }}>取消</button>
          </div>
        </form>
      )}
    </AppShell>
  );
}
