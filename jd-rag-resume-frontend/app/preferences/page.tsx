"use client";

import { CalendarDays, CircleCheck, Save } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { AppShell } from "../components/AppShell";
import { ChoiceMenu } from "../components/ChoiceMenu";
import { useSessionUser } from "../components/use-session-user";
import { apiRequest, visibleApiErrorMessage } from "../lib/api";
import { preferencePayload, toPreferenceDraft, type JobPreferences, type PreferenceDraft } from "../job-preferences";
import { HOME_PATH } from "../workspace-nav";

const BLANK: PreferenceDraft = {
  months: "", days: "", earliestStartDate: "", preferredDistrict: "", districtRequired: false,
  educationLevel: "", expectedGraduationDate: "", workExperienceYears: "", currentlyStudent: "",
};

export default function JobPreferencesPage() {
  const { user, status, logout } = useSessionUser();
  const [draft, setDraft] = useState<PreferenceDraft>(BLANK);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (status !== "signedIn") return;
    let active = true;
    void apiRequest<JobPreferences>("/api/job-search/preferences").then((value) => {
      if (active) { setDraft(toPreferenceDraft(value)); setLoaded(true); }
    }).catch((reason) => {
      if (active) setError(visibleApiErrorMessage(reason, "读取求职条件失败") ?? "");
    });
    return () => { active = false; };
  }, [status, user?.id]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setSaved(false);
    setError("");
    try {
      const value = await apiRequest<JobPreferences>("/api/job-search/preferences", {
        method: "PUT",
        body: JSON.stringify(preferencePayload(draft)),
      });
      setDraft(toPreferenceDraft(value));
      setSaved(true);
    } catch (reason) {
      setError(visibleApiErrorMessage(reason, "保存失败") ?? "");
    } finally {
      setSaving(false);
    }
  }

  const disabled = !loaded || saving || !user;
  return (
    <AppShell title="我的求职条件" subtitle="让 Agent 知道你能不能去这份实习，未填写的项目会保留为未知。"
      user={user} onLogout={logout} activeView="assistant" back={{ href: HOME_PATH, label: "返回 AI 助手" }}>
      <form className="detail-card" onSubmit={(event) => void save(event)}>
        {error && <p className="toast error" role="alert">{error}</p>}
        {saved && <p className="toast success" role="status"><CircleCheck size={18} aria-hidden="true" />已保存，Agent 下次查询会使用新条件</p>}
        <div className="field-row">
          <label>最多可连续实习几个月
            <input type="number" min={1} max={24} placeholder="留空表示尚未确定"
              value={draft.months} disabled={disabled}
              onChange={(e) => setDraft({ ...draft, months: e.target.value })} />
          </label>
          <label>每周最多能到岗几天
            <input type="number" min={1} max={7} placeholder="留空表示尚未确定"
              value={draft.days} disabled={disabled}
              onChange={(e) => setDraft({ ...draft, days: e.target.value })} />
          </label>
        </div>
        <div className="field-row">
          <label>最早能到岗的日期
            <input type="date" value={draft.earliestStartDate} disabled={disabled}
              onChange={(e) => setDraft({ ...draft, earliestStartDate: e.target.value })} />
          </label>
          <label>优先考虑的地区
            <input type="text" maxLength={80} placeholder="例如：深圳龙岗区"
              value={draft.preferredDistrict} disabled={disabled}
              onChange={(e) => setDraft({ ...draft, preferredDistrict: e.target.value })} />
          </label>
        </div>
        <label className="preference-requirement">
          <input type="checkbox" checked={draft.districtRequired} disabled={disabled}
            onChange={(e) => setDraft({ ...draft, districtRequired: e.target.checked })} />
          必须在该地区（不勾选时仅作为排序偏好）
        </label>
        <div className="field-row">
          <div className="preference-choice">
            <span>本人学历</span>
            <ChoiceMenu label="本人学历" value={draft.educationLevel} disabled={disabled}
              options={[
                { value: "", label: "暂未填写", description: "遇到学历门槛时保留未知" },
                { value: "1", label: "专科", description: "已取得或就读专科学历" },
                { value: "2", label: "本科", description: "已取得或就读本科学历" },
                { value: "3", label: "硕士", description: "已取得或就读硕士学历" },
                { value: "4", label: "博士", description: "已取得或就读博士学历" },
              ]}
              onChange={(value) => setDraft({ ...draft, educationLevel: value })}>
              {({ "": "暂未填写", "1": "专科", "2": "本科", "3": "硕士", "4": "博士" } as Record<string, string>)[draft.educationLevel]}
            </ChoiceMenu>
          </div>
          <label>预计毕业日期
            <input type="date" value={draft.expectedGraduationDate} disabled={disabled}
              onChange={(e) => setDraft({ ...draft, expectedGraduationDate: e.target.value })} />
          </label>
        </div>
        <div className="field-row">
          <label>正式工作经验（年）
            <input type="number" min={0} max={50} placeholder="留空表示尚未填写"
              value={draft.workExperienceYears} disabled={disabled}
              onChange={(e) => setDraft({ ...draft, workExperienceYears: e.target.value })} />
          </label>
          <div className="preference-choice">
            <span>目前在校</span>
            <ChoiceMenu label="目前是否在校" value={draft.currentlyStudent} disabled={disabled}
              options={[
                { value: "", label: "暂未填写", description: "不会根据简历猜测" },
                { value: "yes", label: "在校生", description: "目前仍在学校就读" },
                { value: "no", label: "已离校", description: "目前没有在校就读" },
              ]}
              onChange={(value) => setDraft({ ...draft, currentlyStudent: value })}>
              {({ "": "暂未填写", yes: "在校生", no: "已离校" } as Record<string, string>)[draft.currentlyStudent]}
            </ChoiceMenu>
          </div>
        </div>
        <p className="workspace-subtitle">这些信息由你自己填写，系统不从简历推断。JD 未写学历/经验门槛不会阻断推荐；明确要求但信息缺失则待确认。<CalendarDays size={14} aria-hidden="true" /></p>
        <div className="form-actions">
          <button type="submit" className="primary" disabled={disabled}><Save size={16} aria-hidden="true" />{saving ? "保存中" : "保存条件"}</button>
        </div>
      </form>
    </AppShell>
  );
}
