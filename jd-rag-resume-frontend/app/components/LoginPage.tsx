"use client";

import { ArrowRight, ChartColumn, Eye, EyeOff, FileSearch, LockKeyhole, Mail, ScanSearch, Sparkles, UserRound, type LucideIcon } from "lucide-react";
import { useState, type FormEvent, type ReactNode } from "react";
import { BackendStatus } from "./BackendStatus";
import { BrandImage } from "./BrandArt";

export type AuthMode = "login" | "register";
export type AuthForm = { username: string; password: string; displayName: string; email: string };

/** Only what the product really does; each line maps to a page or a report section. */
const FEATURES: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: FileSearch, title: "精准职位匹配", text: "按语义找到相关岗位" },
  { icon: ChartColumn, title: "优势与缺口", text: "看清长处和短板" },
  { icon: ScanSearch, title: "证据可追溯", text: "结论都能回到原文" },
  { icon: Sparkles, title: "AI 助手", text: "帮你翻投递和记录" },
];

function Field({ icon: Icon, label, children, trailing }: { icon: LucideIcon; label: string; children: ReactNode; trailing?: ReactNode }) {
  return (
    <label className="auth-field">
      {label}
      <span className="auth-input">
        <Icon className="auth-input-icon" size={18} aria-hidden="true" />
        {children}
        {trailing}
      </span>
    </label>
  );
}

export function LoginPage({ mode, onModeChange, form, onFormChange, busy, error, onSubmit, runtimeLabel }: {
  mode: AuthMode;
  onModeChange: (mode: AuthMode) => void;
  form: AuthForm;
  onFormChange: (form: AuthForm) => void;
  busy: boolean;
  error: string;
  onSubmit: (event: FormEvent) => void;
  /** Which model answers right now, so the page never claims more than the backend runs. */
  runtimeLabel: string;
}) {
  const [showPassword, setShowPassword] = useState(false);
  const set = (patch: Partial<AuthForm>) => onFormChange({ ...form, ...patch });
  const login = mode === "login";

  return (
    <main className="auth-shell">
      <section className="auth-story">
        <BrandImage name="login-landscape" className="auth-story-art" priority />
        <div className="auth-brand">
          <span className="brand-mark">R</span>
          <span><strong>ResumeLens</strong><small>让优秀的你，被更好的机会看见</small></span>
        </div>
        <h1>用 AI 看懂你的简历，<br /><em>发现更合适的职业机会</em></h1>
        <p>本地向量检索定位简历证据，{runtimeLabel}。</p>
        <ul className="auth-features">
          {FEATURES.map(({ icon: Icon, title, text }) => (
            <li key={title}>
              <span className="auth-feature-icon" aria-hidden="true"><Icon size={20} /></span>
              <strong>{title}</strong>
              <small>{text}</small>
            </li>
          ))}
        </ul>
      </section>
      <section className="auth-panel">
        <form className="auth-card" onSubmit={onSubmit}>
          <div className="mobile-brand"><span className="brand-mark">R</span> ResumeLens</div>
          <BackendStatus variant="pill" />
          <h2>{login ? "登录 ResumeLens" : "创建账号"}</h2>
          <p>{login ? "开始查看你的简历与职位匹配结果" : "30 秒建立自己的分析空间"}</p>
          <Field icon={UserRound} label="用户名">
            <input required maxLength={64} value={form.username} onChange={(e) => set({ username: e.target.value })} placeholder="请输入用户名" autoComplete="username" />
          </Field>
          {!login && <>
            <Field icon={UserRound} label="显示名称">
              <input required maxLength={80} value={form.displayName} onChange={(e) => set({ displayName: e.target.value })} placeholder="例如 Arthur" />
            </Field>
            <Field icon={Mail} label="邮箱">
              <input required type="email" value={form.email} onChange={(e) => set({ email: e.target.value })} placeholder="name@example.com" autoComplete="email" />
            </Field>
          </>}
          <Field icon={LockKeyhole} label="密码" trailing={
            <button type="button" className="auth-reveal" aria-label={showPassword ? "隐藏密码" : "显示密码"} aria-pressed={showPassword} onClick={() => setShowPassword((value) => !value)}>
              {showPassword ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
            </button>
          }>
            <input required type={showPassword ? "text" : "password"} minLength={6} value={form.password} onChange={(e) => set({ password: e.target.value })} placeholder={login ? "请输入密码" : "至少 6 位"} autoComplete={login ? "current-password" : "new-password"} />
          </Field>
          {error && <div className="message error">{error}</div>}
          <button className="auth-submit" disabled={busy}>
            {busy ? "正在连接…" : login ? "登录" : "注册并进入"}
            {!busy && <ArrowRight size={18} aria-hidden="true" />}
          </button>
          <button className="text-button" type="button" onClick={() => onModeChange(login ? "register" : "login")}>
            {login ? "没有账号？创建一个" : "已有账号？直接登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
