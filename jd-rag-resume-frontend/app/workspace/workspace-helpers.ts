import type {
  AnalysisSummary,
  Job,
  JobSemanticMatch,
} from "../lib/api";
import type { HashView } from "../workspace-nav";

export const EMPTY_RESUME_FORM = {
  title: "",
  candidateName: "",
  phone: "",
  email: "",
  rawText: "",
};

export const EMPTY_JOB_FORM = {
  title: "",
  companyName: "",
  location: "",
  employmentType: "全职",
  description: "",
  requirements: "",
};

export type ResumeFormState = typeof EMPTY_RESUME_FORM;
export type JobFormState = typeof EMPTY_JOB_FORM;

export const HISTORY_PAGE_SIZE = 20;
export const JOB_PAGE_SIZE = 50;

export type JobSort = "recent" | "semantic" | "score" | "analyzed";
export type JobFilter = "all" | "unanalyzed";
export type JobAnalysesStatus = "idle" | "loading" | "ready" | "error";
export type JobSemanticStatus = "idle" | "loading" | "ready" | "error";
export type AnalysisRun = { generation: number; resumeId: number; jobId: number };

export const VIEW_SUBTITLES: Record<HashView, string> = {
  match: "选一份简历和目标岗位，查看匹配报告和简历里的原文证据。",
  resumes: "集中管理你的简历，上传后即可用于岗位匹配与优化。",
  jobs: "浏览职位，结合你的当前简历，快速找到更合适的机会。",
  saved: "收藏保存在当前浏览器，按账号区分；不会跨设备同步。",
  history: "查看你已保存的匹配结果，随时回顾历史分析。",
};

export const JOB_SORT_OPTIONS = [
  { value: "semantic", label: "按与当前简历的语义相关度", shortLabel: "按语义相关度", description: "优先查看与当前简历最接近的岗位" },
  { value: "score", label: "按已分析匹配分", shortLabel: "按匹配分", description: "优先查看完整分析得分较高的岗位" },
  { value: "analyzed", label: "按最近分析", shortLabel: "按最近分析", description: "优先查看刚完成分析的岗位" },
  { value: "recent", label: "按最近保存", shortLabel: "按最近保存", description: "优先查看新加入职位库的岗位" },
] as const satisfies ReadonlyArray<{ value: JobSort; label: string; shortLabel: string; description: string }>;

export function jobSortShortLabel(sort: JobSort): string {
  return JOB_SORT_OPTIONS.find((option) => option.value === sort)?.shortLabel ?? "按语义相关度";
}

export function analysisTimestamp(item?: AnalysisSummary): number {
  const timestamp = item?.createdAt ? Date.parse(item.createdAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : -1;
}

export function analysisScore(item?: AnalysisSummary): number {
  const score = Number(item?.matchScore);
  return item?.status === "COMPLETED" && Number.isFinite(score) ? score : -1;
}

export function jobTimestamp(item: Job): number {
  const timestamp = item.createdAt ? Date.parse(item.createdAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : -1;
}

export type JobLibraryViewInput = {
  jobs: Job[];
  selectedResumeId: number | "";
  jobSort: JobSort;
  jobFilter: JobFilter;
  jobLatestAnalyses: AnalysisSummary[];
  jobAnalysesStatus: JobAnalysesStatus;
  jobAnalysesResumeId: number | null;
  jobSemanticMatches: JobSemanticMatch[];
  jobSemanticStatus: JobSemanticStatus;
  jobSemanticResumeId: number | null;
};

export type JobLibraryView = {
  jobAnalysesLoading: boolean;
  jobAnalysisDataReady: boolean;
  jobSemanticDataReady: boolean;
  jobAnalysisDataRequired: boolean;
  jobSemanticDataRequired: boolean;
  latestAnalysisByJob: Map<number, AnalysisSummary>;
  semanticSimilarityByJob: Map<number, number>;
  visibleJobs: Job[];
  libraryRankingReady: boolean;
  libraryJobs: Job[];
};

/** Derives job-library ranking maps and filtered/sorted lists cleanly without inline IIFEs. */
export function computeJobLibraryView(input: JobLibraryViewInput): JobLibraryView {
  const {
    jobs,
    selectedResumeId,
    jobSort,
    jobFilter,
    jobLatestAnalyses,
    jobAnalysesStatus,
    jobAnalysesResumeId,
    jobSemanticMatches,
    jobSemanticStatus,
    jobSemanticResumeId,
  } = input;

  const jobAnalysesLoading = jobAnalysesStatus === "loading";
  const jobAnalysisDataReady = Boolean(
    selectedResumeId
    && jobAnalysesStatus === "ready"
    && jobAnalysesResumeId === selectedResumeId,
  );
  const jobSemanticDataReady = Boolean(
    selectedResumeId
    && jobSemanticStatus === "ready"
    && jobSemanticResumeId === selectedResumeId,
  );
  const jobAnalysisDataRequired = jobSort === "score" || jobSort === "analyzed" || jobFilter === "unanalyzed";
  const jobSemanticDataRequired = jobSort === "semantic";

  const latestAnalysisByJob = new Map<number, AnalysisSummary>(
    jobAnalysisDataReady
      ? jobLatestAnalyses
          .filter((item) => item.resumeId === selectedResumeId)
          .map((item) => [item.jobDescriptionId, item])
      : [],
  );
  const semanticSimilarityByJob = new Map<number, number>(
    jobSemanticDataReady
      ? jobSemanticMatches.map((item) => [item.job.id, item.similarity])
      : [],
  );

  const libraryRankingReady = (!jobAnalysisDataRequired || jobAnalysisDataReady)
    && (!jobSemanticDataRequired || jobSemanticDataReady);

  let visibleJobs: Job[] = [];
  if (libraryRankingReady) {
    const filtered = jobFilter === "unanalyzed"
      ? jobs.filter((item) => {
          const latest = latestAnalysisByJob.get(item.id);
          return !latest || latest.status === "FAILED";
        })
      : [...jobs];
    filtered.sort((left, right) => compareJobs(left, right, jobSort, latestAnalysisByJob, semanticSimilarityByJob));
    visibleJobs = filtered;
  }

  // Managing saved jobs must not depend on resume selection or ranking services.
  const libraryJobs = libraryRankingReady
    ? visibleJobs
    : [...jobs].sort((left, right) => jobTimestamp(right) - jobTimestamp(left) || right.id - left.id);

  return {
    jobAnalysesLoading,
    jobAnalysisDataReady,
    jobSemanticDataReady,
    jobAnalysisDataRequired,
    jobSemanticDataRequired,
    latestAnalysisByJob,
    semanticSimilarityByJob,
    visibleJobs,
    libraryRankingReady,
    libraryJobs,
  };
}

function compareJobs(
  left: Job,
  right: Job,
  sort: JobSort,
  latestAnalysisByJob: Map<number, AnalysisSummary>,
  semanticSimilarityByJob: Map<number, number>,
): number {
  switch (sort) {
    case "score":
      return analysisScore(latestAnalysisByJob.get(right.id)) - analysisScore(latestAnalysisByJob.get(left.id))
        || right.id - left.id;
    case "semantic":
      return (semanticSimilarityByJob.get(right.id) ?? -1) - (semanticSimilarityByJob.get(left.id) ?? -1)
        || right.id - left.id;
    case "analyzed":
      return analysisTimestamp(latestAnalysisByJob.get(right.id)) - analysisTimestamp(latestAnalysisByJob.get(left.id))
        || right.id - left.id;
    case "recent":
    default:
      return jobTimestamp(right) - jobTimestamp(left) || right.id - left.id;
  }
}
