package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.collect.CollectRequest;
import com.arthur.jdragresume.collect.CollectedListing;
import com.arthur.jdragresume.collect.CollectionResult;
import com.arthur.jdragresume.collect.JobCollector;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectBossJobsToolTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentToolContext context = new AgentToolContext(new AppUser());
    private final FakeCollector collector = new FakeCollector();
    private final RecordingJobs jobs = new RecordingJobs();
    private final com.arthur.jdragresume.collect.JobCollectionHistory history = org.mockito.Mockito.mock(com.arthur.jdragresume.collect.JobCollectionHistory.class);
    private final CollectBossJobsTool tool = new CollectBossJobsTool(List.of(collector), jobs, history);

    @Test
    void aDelegatingCollectorTurnsIntoABrowserCommand() {
        DeferrableTool.Begin begin = tool.begin(arguments(), context);

        DeferrableTool.InBrowser inBrowser = assertInstanceOf(DeferrableTool.InBrowser.class, begin);
        assertEquals("browser-extension", inBrowser.executor());
        assertEquals(new CollectRequest("Java", "深圳", 1), collector.started.getFirst(), "maxPages defaults to 1");
        assertEquals(0, jobs.batches.size());
    }

    @Test
    void aCollectorThatFinishesHereIsSavedAtOnce() {
        collector.finishesHere = new CollectionResult(CollectionResult.Outcome.COMPLETED, "MAX_PAGES", "已读完", 1,
                List.of(listing("a1")), 0, null);
        DeferrableTool.Done done = assertInstanceOf(DeferrableTool.Done.class, tool.begin(arguments(), context));
        assertEquals(1, ((CollectBossJobsTool.Summary) done.result()).created());
        org.mockito.Mockito.verify(history).record(new CollectRequest("Java", "深圳", 1), collector.finishesHere);
    }

    @Test
    void findingShenzhenInternshipsCanUseTheStudentFilterWithoutInventingAKeyword() {
        JsonNode arguments = objectMapper.createObjectNode().put("city", "深圳").put("experience", "在校生");
        assertTrue(tool.parameters().validate(arguments).isEmpty());
        tool.begin(arguments, context);
        assertEquals(new CollectRequest("", "深圳", 1, "在校生"), collector.started.getFirst());
        assertTrue(tool.description().contains("在校生不保证是实习"));
    }

    @Test
    void resumeSavesListingsAsBossSummariesAndReportsWhatHappened() {
        collector.result = new CollectionResult(CollectionResult.Outcome.STOPPED, "CAPTCHA", "需要验证", 1,
                List.of(listing("a1"), listing("b2")), 3, 28);
        jobs.existing = List.of("b2");

        CollectBossJobsTool.Summary summary = (CollectBossJobsTool.Summary) tool.resume(arguments(), report(), context);

        JobCaptureRequest saved = jobs.batches.getFirst().getFirst();
        assertEquals("BOSS", saved.sourcePlatform());
        assertEquals("a1", saved.sourceJobId());
        assertEquals("STOPPED", summary.status());
        assertEquals("CAPTCHA", summary.stopReason());
        assertEquals(null, summary.finishReason());
        assertEquals(2, summary.collected());
        assertEquals(1, summary.created());
        assertEquals(1, summary.alreadyInLibrary());
        assertEquals(List.of(1L, 2L), summary.jobIds());
        assertEquals(Map.of("dom", 2), summary.sources());
        assertTrue(summary.note().contains("不是完整职位描述"));

        CollectBossJobsTool.Facts facts = (CollectBossJobsTool.Facts) tool.stepFacts(summary);
        assertEquals(new CollectBossJobsTool.Facts(1, 2, 1, 1, 0, "CAPTCHA", "需要验证", Map.of("dom", 2)), facts);
    }

    @Test
    void aFinishedRunHasNoStopInItsFacts() {
        collector.result = new CollectionResult(CollectionResult.Outcome.COMPLETED, "MAX_PAGES", "已读完", 2,
                List.of(listing("a1")), 0, 28);
        CollectBossJobsTool.Facts facts = (CollectBossJobsTool.Facts) tool.stepFacts(tool.resume(arguments(), report(), context));
        assertEquals(2, facts.pagesRead());
        assertEquals(null, facts.stopReason());
        assertEquals(null, facts.stopMessage(), "a finished run's status message is not a stop reason");
        assertEquals(null, tool.stepFacts("something else"));
    }

    @Test
    void failuresAndEmptyStopsAreErrorsForTheModelAndSaveNothing() {
        collector.result = CollectionResult.failed("EXTENSION_NOT_FOUND", "没有检测到插件");
        BusinessException missing = assertThrows(BusinessException.class, () -> tool.resume(arguments(), report(), context));
        assertEquals("EXTENSION_NOT_FOUND", missing.getCode());
        assertEquals("没有检测到插件", missing.getMessage());

        collector.result = new CollectionResult(CollectionResult.Outcome.STOPPED, "BOSS_NOT_LOGGED_IN", "未登录", 0, List.of(), 0, 30);
        assertEquals("BOSS_NOT_LOGGED_IN", assertThrows(BusinessException.class, () -> tool.resume(arguments(), report(), context)).getCode());
        assertEquals(0, jobs.batches.size());
        org.mockito.Mockito.verifyNoInteractions(history);
    }

    @Test
    void aFullLibraryIsReportedNotHidden() {
        collector.result = new CollectionResult(CollectionResult.Outcome.COMPLETED, "MAX_PAGES", "已读完", 1,
                List.of(listing("a1"), listing("b2")), 0, null);
        jobs.quotaLeft = 1;

        CollectBossJobsTool.Summary summary = (CollectBossJobsTool.Summary) tool.resume(arguments(), report(), context);

        assertEquals(1, summary.notSaved());
        assertTrue(summary.quotaMessage().contains("200"));
    }

    @Test
    void anOversizedResultDropsJobRowsBeforeAnyId() {
        Iterator<String> names = objectMapper.valueToTree(new CollectBossJobsTool.Summary(
                "k", "c", "在校生", "COMPLETED", null, null, null, 0, 0, 0, 0, 0, null, Map.of(), 0, null,
                List.of(), List.of(), "n")).fieldNames();
        List<String> order = new ArrayList<>();
        names.forEachRemaining(order::add);
        assertTrue(order.indexOf("jobs") < order.indexOf("jobIds"), "ToolEnvelope trims the first list it finds");
    }

    private JsonNode arguments() {
        return objectMapper.createObjectNode().put("keyword", "Java").put("city", "深圳");
    }

    private JsonNode report() {
        return objectMapper.createObjectNode().put("type", "result");
    }

    private static CollectedListing listing(String id) {
        return new CollectedListing("Java " + id, "示例科技", "深圳", "全职", "摘要", "本科",
                "https://www.zhipin.com/job_detail/" + id + ".html", id, "dom");
    }

    static final class FakeCollector implements JobCollector {
        final List<CollectRequest> started = new ArrayList<>();
        CollectionResult finishesHere;
        CollectionResult result;

        @Override
        public String platform() {
            return "BOSS";
        }

        @Override
        public Start start(CollectRequest request) {
            started.add(request);
            return finishesHere != null ? new Collected(finishesHere)
                    : new Delegated("browser-extension", "collectBossJobs", Map.of("keyword", request.keyword()));
        }

        @Override
        public CollectionResult complete(CollectRequest request, JsonNode report) {
            return result;
        }
    }

    /** Records every batch; ids are positions, {@code existing} source ids are reported as already saved. */
    static final class RecordingJobs extends JobDescriptionService {
        final List<List<JobCaptureRequest>> batches = new ArrayList<>();
        List<String> existing = List.of();
        int quotaLeft = Integer.MAX_VALUE;

        RecordingJobs() {
            super(null, null, null, null, null, 200);
        }

        @Override
        public ListingCaptureResult captureListings(List<JobCaptureRequest> listings) {
            batches.add(listings);
            List<ListingCaptureResult.Saved> saved = new ArrayList<>();
            int notSaved = 0;
            int left = quotaLeft;
            for (JobCaptureRequest listing : listings) {
                boolean known = existing.contains(listing.sourceJobId());
                if (!known && left-- <= 0) {
                    notSaved++;
                    continue;
                }
                JobDescriptionResponse job = new JobDescriptionResponse((long) saved.size() + 1, 1L, listing.title(),
                        listing.companyName(), listing.location(), listing.employmentType(), listing.description(),
                        listing.requirements(), "BOSS", listing.sourceUrl(), listing.sourceJobId(), null, null,
                        known ? "FULL" : "LISTING", null, null);
                saved.add(new ListingCaptureResult.Saved(job, known));
            }
            return new ListingCaptureResult(saved, notSaved, notSaved > 0, 200);
        }
    }
}
