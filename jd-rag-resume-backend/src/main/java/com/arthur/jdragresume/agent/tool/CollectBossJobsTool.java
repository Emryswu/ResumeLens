package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.collect.CollectRequest;
import com.arthur.jdragresume.collect.JobCollectionHistory;
import org.springframework.transaction.annotation.Transactional;
import com.arthur.jdragresume.collect.CollectionResult;
import com.arthur.jdragresume.collect.JobCollector;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Collects BOSS search listings into the job library. The tool only knows {@link JobCollector}:
 * whether the collector finishes on the server or delegates to the user's browser decides
 * whether the turn pauses, nothing else. Results are summaries and are saved as such
 * ({@link JobDescriptionService#captureListings}), never over a full posting.
 */
@Component
public class CollectBossJobsTool implements DeferrableTool {
    static final String PLATFORM = "BOSS";

    private final JobCollector collector;
    private final JobDescriptionService jobDescriptionService;

    private final JobCollectionHistory history;

    public CollectBossJobsTool(List<JobCollector> collectors, JobDescriptionService jobDescriptionService, JobCollectionHistory history) {
        this.collector = collectors.stream().filter(candidate -> PLATFORM.equals(candidate.platform())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no job collector for " + PLATFORM));
        this.jobDescriptionService = jobDescriptionService;
        this.history = history;
    }

    @Override
    public String name() {
        return "collect_boss_jobs";
    }

    @Override
    public String description() {
        return "让用户浏览器里的 ResumeLens 插件打开 BOSS 直聘搜索页，只读采集岗位列表（每次最多 3 页、45 条），存进职位库。"
                + "用户要求找工作、找实习、校招或去 BOSS 搜索时可调用，不要因已有职位库搜索为空就断言没有岗位。"
                + "BOSS 用法：城市放 city，技术方向放 keyword，学生身份或年限放 experience，不要把所有条件塞进关键词。"
                + "例如“找深圳 Java 后端实习”用 city=深圳、keyword=Java 后端、experience=在校生；"
                + "“找深圳实习”没指定方向时 keyword 可留空、experience=在校生。校招可用 experience=应届生辅助筛选。"
                + "网页的求职类型是全职/兼职，实习相关人群筛选在工作经验里；在校生不保证是实习，应届生不保证是校招，需结合岗位内容核实。"
                + "需要用户装好插件、授权访问 BOSS 并已登录，否则返回 ok=false，"
                + "如实转告原因即可。采集到的是列表摘要（职位名、公司、薪资、标签），不是完整 JD；"
                + "需要排序时把返回的 jobIds 传给 rank_jobs_for_resume，粗排相似度不是匹配分。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .string("keyword", "岗位方向，如 Java 后端；城市和学生身份用对应筛选参数。指定 experience 时可留空", false, 50)
                .string("city", "城市：全国、北京、上海、广州、深圳、杭州、南京、苏州、成都、武汉、西安、天津、重庆、长沙、厦门", true, 10)
                .string("experience", "BOSS 工作经验筛选：不限、在校生、应届生、经验不限、1年以内、1-3年、3-5年、5-10年、10年以上；默认不限。找实习优先在校生", false, 10)
                .integer("maxPages", "读取页数，默认 1，最多 3", false, 1, 3);
    }

    @Override
    @Transactional
    public Begin begin(JsonNode arguments, AgentToolContext context) {
        JobCollector.Start start = collector.start(request(arguments));
        return switch (start) {
            case JobCollector.Collected collected -> new Done(save(request(arguments), collected.result()));
            case JobCollector.Delegated delegated -> new InBrowser(delegated.executor(), delegated.action(), delegated.command());
        };
    }

    @Override
    @Transactional
    public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
        CollectRequest request = request(arguments);
        return save(request, collector.complete(request, report));
    }

    private static CollectRequest request(JsonNode arguments) {
        return new CollectRequest(
                arguments.path("keyword").asText(""),
                arguments.path("city").asText(""),
                arguments.path("maxPages").asInt(1),
                arguments.path("experience").asText("不限"));
    }

    /**
     * Saves what was read. Nothing read and no usable outcome is an error the model must pass on
     * (no extension, no permission, not logged in...); a stop after some pages keeps those pages.
     */
    private Summary save(CollectRequest request, CollectionResult result) {
        if (result.outcome() == CollectionResult.Outcome.FAILED
                || result.outcome() == CollectionResult.Outcome.STOPPED && result.listings().isEmpty()) {
            throw new BusinessException(result.code(), result.message());
        }
        ListingCaptureResult saved = jobDescriptionService.captureListings(result.listings().stream()
                .map(listing -> new JobCaptureRequest(
                        listing.title(),
                        listing.companyName(),
                        listing.location(),
                        listing.employmentType(),
                        listing.description(),
                        listing.requirements(),
                        PLATFORM,
                        listing.sourceUrl(),
                        listing.sourceJobId()))
                .toList());
        history.record(request, result);
        List<Job> jobs = saved.saved().stream()
                .map(item -> new Job(item.job().id(), item.job().title(), item.job().companyName(),
                        item.job().location(), !item.existingJob()))
                .toList();
        int created = (int) jobs.stream().filter(Job::newlySaved).count();
        boolean stopped = result.outcome() == CollectionResult.Outcome.STOPPED;
        return new Summary(
                request.keyword(),
                request.city(),
                request.experience(),
                result.outcome().name(),
                stopped ? null : result.code(),
                stopped ? result.code() : null,
                result.message(),
                result.pagesRead(),
                result.listings().size(),
                created,
                jobs.size() - created,
                saved.notSaved(),
                saved.quotaReached() ? "职位库已达上限 " + saved.quota() + " 条，" + saved.notSaved() + " 条新岗位没有保存" : null,
                result.sources(),
                result.skippedWithoutId(),
                result.dailyPagesLeft(),
                jobs,
                jobs.stream().map(Job::jobId).toList(),
                "这些是 BOSS 搜索列表摘要，不是完整职位描述；粗排相似度不是匹配分。要读完整 JD，需要用户在职位详情页用插件弹窗抓取。");
    }

    @Override
    public Object stepFacts(Object result) {
        if (!(result instanceof Summary summary)) return null;
        return new Facts(summary.pagesRead(), summary.collected(), summary.created(), summary.alreadyInLibrary(),
                summary.notSaved(), summary.stopReason(), summary.stopReason() == null ? null : summary.statusMessage(),
                summary.sources());
    }

    /** {@code stopMessage} is the server's own text for {@code stopReason}, never the extension's. */
    record Facts(int pagesRead, int collected, int created, int alreadyInLibrary, int notSaved, String stopReason,
                 String stopMessage, Map<String, Integer> sources) {
    }

    /** {@code jobs} comes before {@code jobIds}: an oversized result drops job rows first and keeps every id. */
    record Summary(
            String keyword,
            String city,
            String experience,
            String status,
            String finishReason,
            String stopReason,
            String statusMessage,
            int pagesRead,
            int collected,
            int created,
            int alreadyInLibrary,
            int notSaved,
            String quotaMessage,
            Map<String, Integer> sources,
            int skippedWithoutId,
            Integer dailyPagesLeft,
            List<Job> jobs,
            List<Long> jobIds,
            String note
    ) {
    }

    record Job(Long jobId, String title, String companyName, String location, boolean newlySaved) {
    }
}
