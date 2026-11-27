import { ArrowRight, Bookmark } from "lucide-react";
import type { AnalysisSummary, Job } from "../../lib/api";
import { JobCard } from "../JobCard";
import { EmptyState } from "../StateBlock";

type SavedJobsSectionProps = {
  hidden: boolean;
  jobs: Job[];
  savedJobs: number[];
  effectiveJobsTotal: number;
  selectedJobId: number | "";
  busy: string;
  jobsLoadingMore: boolean;
  latestAnalysisByJob: Map<number, AnalysisSummary>;
  semanticSimilarityByJob: Map<number, number>;
  onGoToMatch: () => void;
  onUseJob: (jobId: number) => void;
  onToggleBookmark: (jobId: number) => void;
  onLoadAllJobsForRanking: () => void;
};

export function SavedJobsSection({
  hidden,
  jobs,
  savedJobs,
  effectiveJobsTotal,
  selectedJobId,
  busy,
  jobsLoadingMore,
  latestAnalysisByJob,
  semanticSimilarityByJob,
  onGoToMatch,
  onUseJob,
  onToggleBookmark,
  onLoadAllJobsForRanking,
}: SavedJobsSectionProps) {
  return (
    <section id="saved" className="saved-view" hidden={hidden}>
      {savedJobs.length === 0 ? (
        <div className="state-panel">
          <EmptyState
            icon={Bookmark}
            art="empty-jobs"
            title="还没有保存的岗位"
            text="在岗位或报告旁点「保存」，就能在这里继续准备。"
            action={<button className="primary" type="button" onClick={onGoToMatch}>去看目标岗位<ArrowRight size={16} aria-hidden="true" /></button>}
          />
        </div>
      ) : (
        <>
          <div className="job-list">
            {jobs.filter((item) => savedJobs.includes(item.id)).map((item) => (
              <JobCard
                key={item.id}
                job={item}
                analysis={latestAnalysisByJob.get(item.id)}
                similarity={semanticSimilarityByJob.get(item.id)}
                prefer="score"
                selected={selectedJobId === item.id}
                saved
                disabled={!!busy}
                onUse={() => onUseJob(item.id)}
                onToggleSave={() => onToggleBookmark(item.id)}
              />
            ))}
          </div>
          {jobs.length < effectiveJobsTotal && (
            <button className="ghost" disabled={jobsLoadingMore} onClick={onLoadAllJobsForRanking}>
              加载其余岗位中的收藏
            </button>
          )}
          <p className="review-caveat">仅展示仍可访问的岗位。收藏范围：当前浏览器、当前账号。</p>
        </>
      )}
    </section>
  );
}
