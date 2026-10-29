package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.JobSearchStopPolicy;
import com.arthur.jdragresume.collect.CollectRequest;
import com.arthur.jdragresume.collect.JobCollectionHistory;
import org.springframework.transaction.annotation.Transactional;
import com.arthur.jdragresume.collect.CollectionResult;
import com.arthur.jdragresume.collect.JobCollector;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ToolNotStartedException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
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

    public CollectBossJobsTool(JobCollector collector, JobDescriptionService jobDescriptionService, JobCollectionHistory history) {
        this.collector = collector;
        this.jobDescriptionService = jobDescriptionService;
        this.history = history;
    }

    @Override
    public String name() {
        return "collect_boss_jobs";
    }

    @Override
    public String description() {
        return "按用户界面选定的采集方式打开 BOSS 直聘搜索页，只读采集岗位列表（每次最多 3 页、45 条），存进职位库。"
                + "用户要求找工作、找实习、校招或去 BOSS 搜索时可调用，不要因已有职位库搜索为空就断言没有岗位。"
                + "BOSS 用法：城市放 city，技术方向放 keyword，学生身份或年限放 experience，不要把所有条件塞进关键词。"
                + "例如“找深圳 Java 后端实习”用 city=深圳、keyword=Java 后端、jobType=实习；本会话后续不变的参数可省略。明确改成全职传 jobType=全职；清除城市传 city=空字符串；"
                + "“找深圳实习”没指定方向时先 list_resumes 选简历、get_resume 读正文，按简历方向填写非空 keyword，并说明“按你的《xxx》简历推断”；不要直接用空关键词泛搜。"
                + "多份简历遵循系统的方向与更新时间选择规则，同一会话沿用已选简历。校招可用 experience=应届生辅助筛选。"
                + "只有用户明确要求不限方向时才传 broadSearch=true 允许空关键词；否则空关键词会被拒绝，请先根据简历确定方向。"
                + "采集后用选定的 resumeId 与本次 jobIds 调 rank_jobs_for_resume，优先展示对口岗位；不要把不相关岗位当成推荐。"
                + "网页的求职类型是全职/兼职，实习相关人群筛选在工作经验里；在校生不保证是实习，应届生不保证是校招，需结合岗位内容核实。"
                + "采集方式及自动切换由代码处理；用户需在所用浏览器里登录 BOSS。失败时返回 ok=false，"
                + "如实转告原因即可。采集到的是列表摘要（职位名、公司、薪资、标签），不是完整 JD；"
                + "需要排序时把返回的 jobIds 传给 rank_jobs_for_resume，粗排相似度不是匹配分。"
                + JobSearchStopPolicy.guidance();
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .string("keyword", "岗位方向，如 Java 后端；城市和学生身份用对应筛选参数。未指定方向时先读选定简历推断；仅用户明确要求不限方向时可留空", false, 50)
                .bool("broadSearch", "仅用户明确要求不限方向时传 true，允许空 keyword 泛搜；默认 false", false)
                .string("city", "城市：全国、北京、上海、广州、深圳、杭州、南京、苏州、成都、武汉、西安、天津、重庆、长沙、厦门；省略沿用本会话，空字符串清除城市限制", false, 10)
                .string("jobType", "岗位意图：实习、全职、不限；省略沿用，空字符串清除。实习默认 experience=在校生，全职默认 experience=不限", false, 10)
                .string("experience", "BOSS 工作经验筛选：不限、在校生、应届生、经验不限、1年以内、1-3年、3-5年、5-10年、10年以上；默认不限。找实习优先在校生", false, 10)
                .integer("maxPages", "读取页数，默认 1，最多 3", false, 1, 3);
    }

    @Override
    @Transactional
    public Begin begin(JsonNode arguments, AgentToolContext context) {
        CollectRequest request = request(arguments);
        JobCollector.Start start = collector.start(request, context.collection());
        return switch (start) {
            case JobCollector.Collected collected -> new Done(save(request, collected.result()));
            case JobCollector.Delegated delegated -> new InBrowser(delegated.executor(), delegated.action(), delegated.command());
        };
    }

    @Override
    @Transactional
    public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
        CollectRequest request = request(arguments);
        return save(request, collector.complete(request, report, context.collection()));
    }

    private static CollectRequest request(JsonNode arguments) {
        CollectRequest request = new CollectRequest(
                arguments.path("keyword").asText(""),
                arguments.path("city").asText(""),
                arguments.path("maxPages").asInt(1),
                arguments.path("experience").asText("不限"));
        if (request.keyword().isBlank() && !BooleanNode.TRUE.equals(arguments.get("broadSearch"))) {
            throw new ToolNotStartedException("BOSS_DIRECTION_REQUIRED", "请先根据简历确定方向并填写 keyword；仅用户明确要求不限方向时可传 broadSearch=true 泛搜");
        }
        return request;
    }

    /**
     * Saves what was read. Nothing read and no usable outcome is an error the model must pass on
     * (no extension, no permission, not logged in...); a stop after some pages keeps those pages.
     */
    private Summary save(CollectRequest request, CollectionResult result) {
        if (result.outcome() == CollectionResult.Outcome.FAILED
                || result.outcome() == CollectionResult.Outcome.STOPPED && result.listings().isEmpty()) {
            // The extension refused the parameters before opening BOSS, so the search budget is not spent.
            if ("INVALID_COMMAND".equals(result.code())) throw new ToolNotStartedException(result.code(), result.message());
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
                .toList(), request.experience());
        history.record(request, result);
        List<Job> jobs = saved.saved().stream()
                .map(item -> new Job(item.job().id(), item.job().title(), item.job().companyName(),
                        item.job().location(), item.job().collectionExperience(), !item.existingJob()))
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
                result.pageLog(),
                result.switchReason(),
                "这些是 BOSS 搜索列表摘要，不是完整职位描述；粗排相似度不是匹配分。可对排序靠前的岗位调用 verify_boss_job，由插件自动打开详情页核实完整 JD。");
    }

    @Override
    public Object stepFacts(Object result) {
        if (!(result instanceof Summary summary)) return null;
        return new Facts(summary.pagesRead(), summary.collected(), summary.created(), summary.alreadyInLibrary(),
                summary.notSaved(), summary.stopReason(), summary.stopReason() == null ? null : summary.statusMessage(),
                summary.sources(), summary.finishReason(), summary.statusMessage(), summary.pageLog(), summary.switchReason());
    }

    /** {@code stopMessage} is the server's own text for {@code stopReason}, never the extension's. */
    record Facts(int pagesRead, int collected, int created, int alreadyInLibrary, int notSaved, String stopReason,
                 String stopMessage, Map<String, Integer> sources, String finishReason, String statusMessage,
                 List<CollectionResult.PageRead> pageLog, String switchReason) {
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
            List<CollectionResult.PageRead> pageLog,
            String switchReason,
            String note
    ) {
    }

    record Job(Long jobId, String title, String companyName, String location, String collectionExperience, boolean newlySaved) {
    }
}
