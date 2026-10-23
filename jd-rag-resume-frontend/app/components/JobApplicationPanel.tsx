"use client";

import { useEffect, useState, type FormEvent } from "react";
import { ChoiceMenu } from "./ChoiceMenu";
import { APPLICATION_OPTIONS, applicationWrite, type ApplicationStatus } from "../job-application";
import {
  apiRequest, getAuthSessionId, isAuthSessionCurrent, visibleApiErrorMessage,
} from "../lib/api";

type Application = {
  jobId: number;
  status: ApplicationStatus;
  note: string;
  updatedAt: string | null;
};

/** User-entered application tracking. No browser extension sends applications or talks to HR. */
export function JobApplicationPanel({ jobId }: { jobId: number }) {
  const [status, setStatus] = useState<ApplicationStatus>("UNTRACKED");
  const [note, setNote] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const session = getAuthSessionId();
    let active = true;
    void apiRequest<Application>(`/api/job-applications/${jobId}`).then((entry) => {
      if (!active || !isAuthSessionCurrent(session)) return;
      setStatus(entry.status);
      setNote(entry.note);
    }).catch((reason) => {
      if (!active || !isAuthSessionCurrent(session)) return;
      setError(visibleApiErrorMessage(reason, "读取投递进度失败") ?? "");
    }).finally(() => {
      if (active && isAuthSessionCurrent(session)) setLoading(false);
    });
    return () => { active = false; };
  }, [jobId]);

  async function save(event: FormEvent) {
    event.preventDefault();
    const session = getAuthSessionId();
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const request = applicationWrite(status, note);
      if (request.method === "DELETE") {
        await apiRequest<void>(`/api/job-applications/${jobId}`, request);
        if (isAuthSessionCurrent(session)) {
          setNote("");
          setNotice("已清除投递记录");
        }
      } else {
        const updated = await apiRequest<Application>(`/api/job-applications/${jobId}`, request);
        if (isAuthSessionCurrent(session)) {
          setStatus(updated.status);
          setNote(updated.note);
          setNotice("投递进度已保存");
        }
      }
    } catch (reason) {
      if (!isAuthSessionCurrent(session)) return;
      setError(visibleApiErrorMessage(reason, "保存投递进度失败") ?? "");
    } finally {
      if (isAuthSessionCurrent(session)) setSaving(false);
    }
  }

  return (
    <section className="detail-card" aria-label="投递进度与反馈">
      <h2>投递进度与反馈</h2>
      <p>这里只保存你自己填写的进展，不会向 BOSS 投递简历或联系 HR。标记“不合适”并写下原因，AI 助手后续推荐时会参考。</p>
      {loading ? <p role="status">正在读取投递记录…</p> : (
        <form onSubmit={(event) => void save(event)}>
          <div className="field-row">
            <div>
              <span>当前状态</span>
              <ChoiceMenu label="投递状态" value={status} options={APPLICATION_OPTIONS}
                disabled={saving} onChange={setStatus}>
                <span>{APPLICATION_OPTIONS.find((option) => option.value === status)?.label}</span>
              </ChoiceMenu>
            </div>
          </div>
          <label>反馈与备注（最多 300 字）
            <textarea rows={3} maxLength={300} disabled={saving} value={note}
              placeholder="例如：要求每周到岗六天，或学历不符合"
              onChange={(event) => setNote(event.target.value)} />
          </label>
          {error && <p role="alert">{error}</p>}
          {notice && <p role="status">{notice}</p>}
          <div className="form-actions">
            <button type="submit" className="secondary" disabled={saving}>
              {saving ? "保存中…" : status === "UNTRACKED" ? "清除记录" : "保存进度"}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
