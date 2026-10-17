"use client";

import { CircleAlert, CircleCheck, FileText, RotateCw, X } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { AppShell } from "../../components/AppShell";
import { EmptyState, ErrorState, Skeleton } from "../../components/StateBlock";
import { useSessionUser } from "../../components/use-session-user";
import { apiRequest, visibleApiErrorMessage, type Job } from "../../lib/api";
import { loadState } from "../../view-states";

export default function JobDetailPage() {
  const { user, logout } = useSessionUser();
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = Number(params.id);
  const [job, setJob] = useState<Job | null>(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [notice, setNotice] = useState("");
  const validId = Number.isFinite(id) && id > 0;
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
      setLoadError("无效的职位 ID");
      return;
    }
    setBusy("load");
    setError("");
    setLoadError("");
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

  // A failed refresh keeps the 职位 on screen and reports in the toast; with nothing loaded the page shows the retry.
  const view = loadState({ hasData: !!job, loading: busy === "load" || !loadError, error: loadError });
  const toastError = error || (job ? loadError : "");

  return (
    <AppShell
      title={job ? job.title : `职位 #${params.id}`}
      activeView="jobs"
      back={{ href: "/#jobs", label: "职位库" }}
      user={user}
      onLogout={logout}
      actions={
        <>
          <button className="ghost" type="button" onClick={() => void load()} disabled={!!busy || !validId}>
            <RotateCw size={16} aria-hidden="true" />刷新
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
      {(toastError || notice) && (
        <div className={`toast ${toastError ? "error" : "success"}`} role={toastError ? "alert" : "status"}>
          {toastError ? <CircleAlert size={20} aria-hidden="true" /> : <CircleCheck size={20} aria-hidden="true" />}
          {toastError || notice}
          <button type="button" aria-label="关闭通知" onClick={() => { setError(""); setLoadError(""); setNotice(""); }}><X size={18} aria-hidden="true" /></button>
        </div>
      )}

      {view === "loading" && <Skeleton className="detail-skeleton" label="正在读取职位" lines={6} />}
      {view === "error" && (
        <div className="state-panel detail-state">
          <ErrorState title="职位没有加载出来" text={loadError} onRetry={validId ? () => void load() : undefined}
            action={<Link className="ghost" href="/#jobs">返回职位库</Link>} />
        </div>
      )}

      {job && !editing && (
        <section className="detail-card">
          <div className="detail-meta">
            <div><span>ID</span><strong>#{job.id}</strong></div>
            <div><span>公司</span><strong title={job.companyName}>{job.companyName}</strong></div>
            <div><span>地点</span><strong title={job.location || "—"}>{job.location || "—"}</strong></div>
            <div><span>类型</span><strong title={job.employmentType || "—"}>{job.employmentType || "—"}</strong></div>
            <div><span>更新</span><strong>{job.updatedAt ? new Date(job.updatedAt).toLocaleString("zh-CN") : "—"}</strong></div>
          </div>
          <h2>岗位描述</h2>
          {job.description
            ? <pre className="detail-body">{job.description}</pre>
            : <EmptyState compact icon={FileText} title="还没有填写岗位描述" text="点「编辑」补充后，匹配时会用到这部分内容。"
              action={<button className="ghost" type="button" onClick={() => setEditing(true)} disabled={!!busy}>编辑</button>} />}
          <h2>任职要求</h2>
          {job.requirements
            ? <pre className="detail-body">{job.requirements}</pre>
            : <EmptyState compact icon={FileText} title="还没有填写任职要求" text="点「编辑」补充后，匹配时会用到这部分内容。"
              action={<button className="ghost" type="button" onClick={() => setEditing(true)} disabled={!!busy}>编辑</button>} />}
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
