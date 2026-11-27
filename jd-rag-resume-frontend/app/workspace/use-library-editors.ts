import { useState } from "react";
import type { ChangeEvent, Dispatch, FormEvent, SetStateAction } from "react";
import {
  SAMPLE_BULK_JOBS,
  apiRequest,
  parseJobImportPayload,
  type Analysis,
  type Job,
  type Resume,
  type User,
} from "../lib/api";
import { resumeFileProblem } from "../resume-list";
import { appendResumeUploadFields, prepareResumeUploadDraft, resumeFormFrom } from "../resume-upload";
import { SAMPLE_JOB, SAMPLE_RESUME } from "../sample-data";
import type { JobSource } from "../workbench";
import type { HashView } from "../workspace-nav";
import {
  EMPTY_JOB_FORM,
  EMPTY_RESUME_FORM,
  type JobFormState,
  type ResumeFormState,
} from "./workspace-helpers";

const DEFAULT_BULK_IMPORT_TEXT = JSON.stringify(SAMPLE_BULK_JOBS, null, 2);

type UseLibraryEditorsParams = {
  user: User | null;
  selectedResumeId: number | "";
  selectedJobId: number | "";
  analysis: Analysis | null;
  setAnalysis: Dispatch<SetStateAction<Analysis | null>>;
  setResumes: Dispatch<SetStateAction<Resume[]>>;
  setJobs: Dispatch<SetStateAction<Job[]>>;
  setJobsTotal: Dispatch<SetStateAction<number>>;
  chooseResume: (next: number | "") => void;
  chooseJob: (next: number | "") => void;
  invalidateSemanticMatches: () => void;
  setJobSource: Dispatch<SetStateAction<JobSource>>;
  setMatchMode: Dispatch<SetStateAction<"compose" | "report">>;
  navigate: (next: HashView) => void;
  setBusy: Dispatch<SetStateAction<string>>;
  setError: Dispatch<SetStateAction<string>>;
  setNotice: Dispatch<SetStateAction<string>>;
  reportRequestError: (reason: unknown, fallback: string) => void;
};

export function useLibraryEditors({
  user,
  selectedResumeId,
  selectedJobId,
  analysis,
  setAnalysis,
  setResumes,
  setJobs,
  setJobsTotal,
  chooseResume,
  chooseJob,
  invalidateSemanticMatches,
  setJobSource,
  setMatchMode,
  navigate,
  setBusy,
  setError,
  setNotice,
  reportRequestError,
}: UseLibraryEditorsParams) {
  const [file, setFile] = useState<File | null>(null);
  const [resumeForm, setResumeForm] = useState<ResumeFormState>({ ...EMPTY_RESUME_FORM });
  const [jobForm, setJobForm] = useState<JobFormState>({ ...EMPTY_JOB_FORM });
  const [editingResumeId, setEditingResumeId] = useState<number | null>(null);
  const [editingJobId, setEditingJobId] = useState<number | null>(null);
  const [bulkImportText, setBulkImportText] = useState(DEFAULT_BULK_IMPORT_TEXT);
  const [showBulkImport, setShowBulkImport] = useState(false);
  const [showJobEditor, setShowJobEditor] = useState(false);
  const [showResumeEditor, setShowResumeEditor] = useState(false);
  const [resumeDragging, setResumeDragging] = useState(false);

  function pickResumeFile(nextFile: File | null | undefined) {
    if (!nextFile) return;
    const problem = resumeFileProblem(nextFile);
    if (problem) {
      setError(problem);
      return;
    }
    setError("");
    setEditingResumeId(null);
    setFile(nextFile);
    setResumeForm((current) => {
      const draft = prepareResumeUploadDraft(current, nextFile.name);
      return { ...draft, candidateName: draft.candidateName || user?.displayName || "" };
    });
    setShowResumeEditor(true);
    requestAnimationFrame(() => document.getElementById("resume-editor")?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }

  function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    pickResumeFile(event.target.files?.[0]);
    // Picking the same file again after cancelling must still fire onChange.
    event.target.value = "";
  }

  function openPasteResume() {
    setEditingResumeId(null);
    setFile(null);
    setResumeForm({ ...EMPTY_RESUME_FORM, candidateName: user?.displayName || "" });
    setShowResumeEditor(true);
    requestAnimationFrame(() => document.getElementById("resume-editor")?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }

  async function beginEditResume(item: Resume) {
    setBusy("resume-edit");
    setError("");
    try {
      const detail = await apiRequest<Resume>(`/api/resumes/${item.id}`);
      navigate("resumes");
      setEditingResumeId(detail.id);
      setFile(null);
      setResumeForm(resumeFormFrom(detail));
      setShowResumeEditor(true);
      requestAnimationFrame(() => document.getElementById("resume-editor")?.scrollIntoView({ behavior: "smooth", block: "start" }));
      chooseResume(detail.id);
      setNotice(`正在编辑简历 #${detail.id}，保存后将更新并失效旧向量索引`);
    } catch (reason) {
      reportRequestError(reason, "加载简历失败");
    } finally {
      setBusy("");
    }
  }

  function beginEditJob(item: Job) {
    navigate("jobs");
    setShowJobEditor(true);
    requestAnimationFrame(() => document.getElementById("job-editor")?.scrollIntoView({ behavior: "smooth", block: "start" }));
    setEditingJobId(item.id);
    setJobForm({
      title: item.title || "",
      companyName: item.companyName || "",
      location: item.location || "",
      employmentType: item.employmentType || "全职",
      description: item.description || "",
      requirements: item.requirements || "",
    });
    chooseJob(item.id);
    setNotice(`正在编辑职位 #${item.id}`);
  }

  function resetResumeEditor() {
    setEditingResumeId(null);
    setFile(null);
    setResumeForm({ ...EMPTY_RESUME_FORM });
    setShowResumeEditor(false);
  }

  function resetJobEditor() {
    setEditingJobId(null);
    setJobForm({ ...EMPTY_JOB_FORM });
  }

  function applySampleDrafts() {
    setFile(null);
    setEditingResumeId(null);
    setEditingJobId(null);
    setResumeForm({ ...SAMPLE_RESUME });
    setJobForm({ ...SAMPLE_JOB });
  }

  function fillSampleForms() {
    applySampleDrafts();
    setShowResumeEditor(true);
    setNotice("已填入示例简历与岗位，保存后即可返回岗位匹配");
  }

  function resetAllEditors() {
    setEditingResumeId(null);
    setEditingJobId(null);
    setFile(null);
    setResumeForm({ ...EMPTY_RESUME_FORM });
    setJobForm({ ...EMPTY_JOB_FORM });
    setBulkImportText(DEFAULT_BULK_IMPORT_TEXT);
    setShowBulkImport(false);
    setShowJobEditor(false);
    setShowResumeEditor(false);
    setResumeDragging(false);
  }

  async function saveResume(event: FormEvent) {
    event.preventDefault();
    setBusy("resume");
    setError("");
    try {
      let saved: Resume;
      if (editingResumeId && file) {
        throw new Error("编辑模式下不支持重新上传文件；请先取消编辑，或清空文件后仅更新文本");
      }
      if (editingResumeId) {
        saved = await apiRequest<Resume>(`/api/resumes/${editingResumeId}`, {
          method: "PUT",
          body: JSON.stringify(resumeForm),
        });
        setResumes((items) => items.map((item) => (item.id === saved.id ? saved : item)));
        setNotice(`简历 #${saved.id} 已更新，下次匹配会重建向量`);
      } else if (file) {
        const form = new FormData();
        form.append("file", file);
        appendResumeUploadFields(form, resumeForm);
        saved = await apiRequest<Resume>("/api/resumes/upload", { method: "POST", body: form });
        setResumes((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setNotice("简历已上传保存，等待向量检索");
      } else {
        saved = await apiRequest<Resume>("/api/resumes", { method: "POST", body: JSON.stringify(resumeForm) });
        setResumes((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setNotice("简历已保存，等待向量检索");
      }
      chooseResume(saved.id);
      invalidateSemanticMatches();
      resetResumeEditor();
    } catch (reason) {
      reportRequestError(reason, "简历保存失败");
    } finally {
      setBusy("");
    }
  }

  async function saveJob(event: FormEvent) {
    event.preventDefault();
    setBusy("job");
    setError("");
    try {
      let saved: Job;
      if (editingJobId) {
        saved = await apiRequest<Job>(`/api/job-descriptions/${editingJobId}`, {
          method: "PUT",
          body: JSON.stringify(jobForm),
        });
        setJobs((items) => items.map((item) => (item.id === saved.id ? saved : item)));
        setNotice(`职位 #${saved.id} 已更新`);
      } else {
        saved = await apiRequest<Job>("/api/job-descriptions", { method: "POST", body: JSON.stringify(jobForm) });
        setJobs((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setJobsTotal((total) => total + 1);
        setNotice("职位 JD 已保存，可以开始匹配");
      }
      chooseJob(saved.id);
      invalidateSemanticMatches();
      setEditingJobId(null);
      setJobSource("library");
      setMatchMode("compose");
      navigate("match");
    } catch (reason) {
      reportRequestError(reason, "JD 保存失败");
    } finally {
      setBusy("");
    }
  }

  async function deleteResume(id: number) {
    if (!window.confirm(`确认删除简历 #${id}？将同时清理上传文件与向量索引。`)) return;
    setBusy("resume-delete");
    setError("");
    try {
      await apiRequest<void>(`/api/resumes/${id}`, { method: "DELETE" });
      setResumes((items) => items.filter((item) => item.id !== id));
      if (selectedResumeId === id) chooseResume("");
      if (editingResumeId === id) resetResumeEditor();
      if (analysis?.resumeId === id) setAnalysis(null);
      setNotice(`简历 #${id} 已删除`);
    } catch (reason) {
      reportRequestError(reason, "删除简历失败");
    } finally {
      setBusy("");
    }
  }

  async function deleteJob(id: number) {
    if (!window.confirm(`确认删除职位 #${id}？`)) return;
    setBusy("job-delete");
    setError("");
    try {
      await apiRequest<void>(`/api/job-descriptions/${id}`, { method: "DELETE" });
      setJobs((items) => items.filter((item) => item.id !== id));
      setJobsTotal((total) => Math.max(0, total - 1));
      if (selectedJobId === id) chooseJob("");
      if (editingJobId === id) resetJobEditor();
      if (analysis?.jobDescriptionId === id) setAnalysis(null);
      invalidateSemanticMatches();
      setNotice(`职位 #${id} 已删除`);
    } catch (reason) {
      reportRequestError(reason, "删除职位失败");
    } finally {
      setBusy("");
    }
  }

  async function bulkImportJobs() {
    setBusy("bulk-import");
    setError("");
    try {
      const items = parseJobImportPayload(bulkImportText);
      const imported = await apiRequest<Job[]>("/api/job-descriptions/import", {
        method: "POST",
        body: JSON.stringify({ items }),
      });
      setJobs((current) => {
        const merged = [...imported, ...current];
        const seen = new Set<number>();
        return merged.filter((item) => {
          if (seen.has(item.id)) return false;
          seen.add(item.id);
          return true;
        });
      });
      setJobsTotal((total) => total + imported.length);
      if (imported[0]) chooseJob(imported[0].id);
      invalidateSemanticMatches();
      setShowBulkImport(false);
      setNotice(`已批量导入 ${imported.length} 条职位（POST /api/job-descriptions/import）`);
    } catch (reason) {
      reportRequestError(reason, "批量导入失败");
    } finally {
      setBusy("");
    }
  }

  return {
    file,
    resumeForm,
    setResumeForm,
    jobForm,
    setJobForm,
    editingResumeId,
    editingJobId,
    bulkImportText,
    setBulkImportText,
    showBulkImport,
    setShowBulkImport,
    showJobEditor,
    setShowJobEditor,
    showResumeEditor,
    resumeDragging,
    setResumeDragging,
    pickResumeFile,
    chooseFile,
    openPasteResume,
    beginEditResume,
    beginEditJob,
    resetResumeEditor,
    resetJobEditor,
    applySampleDrafts,
    fillSampleForms,
    resetAllEditors,
    saveResume,
    saveJob,
    deleteResume,
    deleteJob,
    bulkImportJobs,
  };
}
