import { ArrowRight, Bookmark, CalendarDays, ChartNoAxesColumn, Clock3, FileText, History, ListChecks, LoaderCircle, Star } from "lucide-react";
import { TIME_RANGES, historyStats, type TimeRange } from "../../history-view";
import type { Analysis, Job, Resume } from "../../lib/api";
import { relativeDay } from "../../resume-list";
import { ChoiceMenu } from "../ChoiceMenu";
import { HistoryRow } from "../HistoryRow";
import { EmptyState, Skeleton } from "../StateBlock";

type HistorySectionProps = {
  hidden: boolean;
  workspaceLoading: boolean;
  history: Analysis[];
  visibleHistory: Analysis[];
  effectiveHistoryTotal: number;
  hasMoreHistory: boolean;
  historyLoadingMore: boolean;
  historyRange: TimeRange;
  historySavedOnly: boolean;
  pageOpenedAt: number;
  resumes: Resume[];
  jobs: Job[];
  savedJobs: number[];
  onHistoryRangeChange: (next: TimeRange) => void;
  onToggleHistorySavedOnly: () => void;
  onResetFilters: () => void;
  onOpenWorkbench: () => void;
  openHistoryReport: (id: number) => void;
  onLoadMoreHistory: () => void;
};

export function HistorySection({
  hidden,
  workspaceLoading,
  history,
  visibleHistory,
  effectiveHistoryTotal,
  hasMoreHistory,
  historyLoadingMore,
  historyRange,
  historySavedOnly,
  pageOpenedAt,
  resumes,
  jobs,
  savedJobs,
  onHistoryRangeChange,
  onToggleHistorySavedOnly,
  onResetFilters,
  onOpenWorkbench,
  openHistoryReport,
  onLoadMoreHistory,
}: HistorySectionProps) {
  const stats = !workspaceLoading && history.length > 0 ? historyStats(history, effectiveHistoryTotal) : null;
  const scope = stats?.partial ? `已加载 ${stats.loaded} / ${stats.total} 条中` : "";

  return (
    <section className="history" id="history" hidden={hidden}>
      {stats && (
        <div className="stat-row history-stats">
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><FileText size={22} /></span>
            <div><strong>{stats.total}<small>条</small></strong><p>已保存的分析记录</p></div>
          </div>
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><ChartNoAxesColumn size={22} /></span>
            <div><strong>{stats.best ?? "—"}{stats.best != null && <small>分</small>}</strong><p>{scope ? `最高匹配分（${scope}）` : "历史最高匹配分"}</p></div>
          </div>
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><Clock3 size={22} /></span>
            <div><strong className="stat-text">{stats.latest ? relativeDay(Date.parse(stats.latest.createdAt), pageOpenedAt) : "—"}</strong><p title={stats.latest?.jobTitle}>最近分析{stats.latest ? ` · ${stats.latest.jobTitle}` : ""}</p></div>
          </div>
          <div className="stat-card">
            <span className="stat-icon" aria-hidden="true"><Star size={22} /></span>
            <div><strong>{stats.strong}<small>个</small></strong><p>{scope ? `匹配分 ≥ 80（${scope}）` : "匹配分 ≥ 80 的记录"}</p></div>
          </div>
        </div>
      )}
      <div className="history-panel">
        {!workspaceLoading && history.length > 0 && (
          <div className="history-filters">
            <div className="history-select">
              <CalendarDays size={16} aria-hidden="true" />
              <ChoiceMenu<TimeRange>
                label="时间范围"
                value={historyRange}
                options={TIME_RANGES.map((range) => ({ value: range.value, label: range.label, description: "按所选时间范围筛选已保存的分析" }))}
                onChange={onHistoryRangeChange}
              >
                {TIME_RANGES.find((range) => range.value === historyRange)?.label ?? "全部时间"}
              </ChoiceMenu>
            </div>
            <button type="button" className="ghost" aria-pressed={historySavedOnly} onClick={onToggleHistorySavedOnly}>
              <Bookmark size={16} aria-hidden="true" />仅看已收藏岗位
            </button>
            <small>{historyRange === "all" && !historySavedOnly ? `${effectiveHistoryTotal} 条记录` : `筛选出 ${visibleHistory.length} 条（已加载的记录中）`}</small>
          </div>
        )}
        <div className="history-grid" role="table" aria-label="分析记录">
          {(workspaceLoading || history.length > 0) && (
            <div className="history-item history-header" role="row">
              <span role="columnheader">简历</span><span role="columnheader">职位</span><span role="columnheader">匹配分</span><span role="columnheader">分析时间</span><span role="columnheader">操作</span>
            </div>
          )}
          {workspaceLoading ? (
            <Skeleton className="skeleton-rows history-skeleton" label="正在读取分析记录" lines={4} />
          ) : history.length === 0 ? (
            <EmptyState
              icon={History}
              art="empty-history"
              title="还没有分析记录"
              text="选择简历和岗位，完成第一次分析后会显示在这里。"
              action={<button className="primary" type="button" onClick={onOpenWorkbench}>去做第一次匹配<ArrowRight size={16} aria-hidden="true" /></button>}
            />
          ) : visibleHistory.length === 0 ? (
            <EmptyState
              compact
              icon={ListChecks}
              title="没有符合条件的记录"
              text="换个时间范围，或取消「仅看已收藏岗位」。"
              action={<button className="ghost" type="button" onClick={onResetFilters}>显示全部记录</button>}
            />
          ) : (
            visibleHistory.map((item) => (
              <HistoryRow
                key={item.id}
                item={item}
                resume={resumes.find((resume) => resume.id === item.resumeId)}
                job={jobs.find((job) => job.id === item.jobDescriptionId)}
                saved={savedJobs.includes(item.jobDescriptionId)}
                onOpen={() => void openHistoryReport(item.id)}
              />
            ))
          )}
        </div>
        {hasMoreHistory && !workspaceLoading && (
          <div className="history-more">
            <button className="ghost" type="button" disabled={historyLoadingMore} onClick={onLoadMoreHistory}>
              {historyLoadingMore
                ? <><LoaderCircle className="spin" size={14} aria-hidden="true" />加载中…</>
                : `继续加载（还有 ${effectiveHistoryTotal - history.length} 条）`}
            </button>
          </div>
        )}
      </div>
    </section>
  );
}
