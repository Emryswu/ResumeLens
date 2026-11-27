import { ChartNoAxesColumn, ClipboardPaste, Clock3, CloudUpload, FileText, Pencil, Upload, X } from "lucide-react";
import type { ChangeEvent, FormEvent, RefObject } from "react";
import type { Resume } from "../../lib/api";
import { RESUME_ACCEPT, resumeStats } from "../../resume-list";
import type { ResumeFormState } from "../../workspace/workspace-helpers";
import { ResumeCard } from "../ResumeCard";
import { EmptyState, Skeleton } from "../StateBlock";

type ResumeLibrarySectionProps = {
  hidden: boolean;
  workspaceLoading: boolean;
  busy: string;
  resumes: Resume[];
  selectedResumeId: number | "";
  effectiveHistoryTotal: number;
  pageOpenedAt: number;
  fileInputRef: RefObject<HTMLInputElement | null>;
  file: File | null;
  editingResumeId: number | null;
  resumeForm: ResumeFormState;
  showResumeEditor: boolean;
  resumeDragging: boolean;
  onSetResumeDragging: (dragging: boolean) => void;
  onPickResumeFile: (file: File | null | undefined) => void;
  onChooseFile: (event: ChangeEvent<HTMLInputElement>) => void;
  onOpenPasteResume: () => void;
  onFillSampleForms: () => void;
  onResetResumeEditor: () => void;
  onSaveResume: (event: FormEvent) => void;
  onResumeFormChange: (next: ResumeFormState) => void;
  onUseResume: (id: number) => void;
  onBeginEditResume: (resume: Resume) => void;
  onDeleteResume: (id: number) => void;
};

export function ResumeLibrarySection({
  hidden,
  workspaceLoading,
  busy,
  resumes,
  selectedResumeId,
  effectiveHistoryTotal,
  pageOpenedAt,
  fileInputRef,
  file,
  editingResumeId,
  resumeForm,
  showResumeEditor,
  resumeDragging,
  onSetResumeDragging,
  onPickResumeFile,
  onChooseFile,
  onOpenPasteResume,
  onFillSampleForms,
  onResetResumeEditor,
  onSaveResume,
  onResumeFormChange,
  onUseResume,
  onBeginEditResume,
  onDeleteResume,
}: ResumeLibrarySectionProps) {
  const stats = !workspaceLoading && resumes.length > 0 ? resumeStats(resumes, pageOpenedAt) : null;
  const editorIcon = editingResumeId
    ? <Pencil size={16} aria-hidden="true" />
    : file
      ? <Upload size={16} aria-hidden="true" />
      : <ClipboardPaste size={16} aria-hidden="true" />;
  const editorTitle = editingResumeId ? "编辑简历" : file ? "确认后上传" : "粘贴简历正文";
  const saveLabel = busy === "resume"
    ? (file ? "上传与解析中…" : "保存中…")
    : (editingResumeId ? "更新简历" : file ? "上传简历" : "保存简历");

  return (
    <section className="resume-view" id="resumes" hidden={hidden}>
      <label
        className={`resume-drop${resumeDragging ? " dragging" : ""}${busy ? " disabled" : ""}`}
        onDragOver={(event) => { event.preventDefault(); if (!busy) onSetResumeDragging(true); }}
        onDragLeave={() => onSetResumeDragging(false)}
        onDrop={(event) => { event.preventDefault(); onSetResumeDragging(false); if (!busy) onPickResumeFile(event.dataTransfer.files?.[0]); }}
      >
        <input ref={fileInputRef} type="file" accept={RESUME_ACCEPT} onChange={onChooseFile} disabled={!!busy} />
        <span className="resume-drop-icon" aria-hidden="true"><CloudUpload size={34} /></span>
        <strong>拖拽简历到这里，或<em>点击上传</em></strong>
        <small>支持 PDF / DOC / DOCX / TXT / MD 格式，文件不超过 20MB</small>
      </label>
      <p className="resume-drop-alt">
        没有文件？
        <button type="button" className="text-action" onClick={onOpenPasteResume}>
          <ClipboardPaste size={15} aria-hidden="true" />直接粘贴简历正文
        </button>
        <button type="button" className="text-action" onClick={onFillSampleForms}>填入示例</button>
      </p>
      {showResumeEditor && (
        <div className="library-editor" id="resume-editor">
          <button className="icon-button library-editor-close" type="button" aria-label="收起简历表单" onClick={onResetResumeEditor}>
            <X size={18} aria-hidden="true" />
          </button>
          <form className="step-card" onSubmit={onSaveResume}>
            <div className="step-title">
              <span>{editorIcon}</span>
              <div>
                <h3>{editorTitle}</h3>
                <p>
                  {editingResumeId
                    ? "修改正文后保存，重新匹配时将使用新内容"
                    : file
                      ? <>文件：<strong className="truncate" title={file.name}>{file.name}</strong>，正文由服务端解析</>
                      : "把简历正文粘贴进来，保存后即可用于匹配"}
                </p>
              </div>
            </div>
            <div className="field-row">
              <label>简历标题<input required maxLength={120} value={resumeForm.title} onChange={(e) => onResumeFormChange({ ...resumeForm, title: e.target.value })} placeholder="Java 后端开发简历" /></label>
              <label>姓名<input required maxLength={80} value={resumeForm.candidateName} onChange={(e) => onResumeFormChange({ ...resumeForm, candidateName: e.target.value })} placeholder="你的姓名" /></label>
            </div>
            <div className="field-row">
              <label>手机<input maxLength={40} value={resumeForm.phone} onChange={(e) => onResumeFormChange({ ...resumeForm, phone: e.target.value })} placeholder="可选" /></label>
              <label>邮箱<input type="email" maxLength={128} value={resumeForm.email} onChange={(e) => onResumeFormChange({ ...resumeForm, email: e.target.value })} placeholder="可选" /></label>
            </div>
            {!file && <label>简历正文<textarea required rows={10} value={resumeForm.rawText} onChange={(e) => onResumeFormChange({ ...resumeForm, rawText: e.target.value })} placeholder="可直接粘贴简历正文" /></label>}
            <div className="form-actions">
              <button className="secondary" disabled={!!busy}>{saveLabel}</button>
              <button className="ghost" type="button" onClick={onResetResumeEditor}>取消</button>
            </div>
          </form>
        </div>
      )}
      {stats && (
        <div className="stat-row">
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><FileText size={22} /></span>
            <div><strong>{stats.count}<small>份简历</small></strong><p>{stats.uploaded > 0 ? `其中 ${stats.uploaded} 份为上传文件` : "都由粘贴正文保存"}</p></div>
          </div>
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><ChartNoAxesColumn size={22} /></span>
            <div><strong>{effectiveHistoryTotal}<small>次分析</small></strong><p>用这些简历做过的匹配</p></div>
          </div>
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><Clock3 size={22} /></span>
            <div><strong>{stats.latest ?? "—"}</strong><p>最近更新时间</p></div>
          </div>
        </div>
      )}
      <div className="library-head"><h2>我的简历列表<em>{resumes.length}</em></h2></div>
      {workspaceLoading ? (
        <Skeleton className="skeleton-rows" label="正在读取简历" lines={3} />
      ) : resumes.length === 0 ? (
        <div className="state-panel">
          <EmptyState
            icon={FileText}
            art="empty-resumes"
            title="还没有简历"
            text="上传一份简历文件，或直接粘贴正文，保存后就能用于岗位匹配。"
            action={<button className="primary" type="button" onClick={() => fileInputRef.current?.click()}><Upload size={16} aria-hidden="true" />上传简历</button>}
          />
        </div>
      ) : (
        <div className="resume-list">
          {resumes.map((item) => (
            <ResumeCard
              key={item.id}
              resume={item}
              selected={selectedResumeId === item.id}
              disabled={!!busy}
              onUse={() => onUseResume(item.id)}
              onEdit={() => onBeginEditResume(item)}
              onDelete={() => onDeleteResume(item.id)}
            />
          ))}
        </div>
      )}
    </section>
  );
}
