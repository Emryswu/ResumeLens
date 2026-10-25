package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.*;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScriptedResumeJobSearchTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final List<String> calls = new ArrayList<>();
    private final List<JsonNode> collections = new ArrayList<>();
    private final List<JsonNode> rankings = new ArrayList<>();
    private List<Map<String, Object>> resumes = List.of(resume(1, "Java 后端简历", "Java Spring Boot Redis", "2026-10-20T10:00:00"));
    private List<Map<String, Object>> jobs = List.of(Map.of("jobId", 7, "title", "Java 后端实习生", "companyName", "示例科技"));
    {
        properties.setMaxSteps(12);
        properties.setMaxPlanningSteps(12);
    }
    private String finishReason = "MAX_PAGES";
    private String stopReason;
    private int pagesRead = 1;

    @Test
    void findingShenzhenInternshipsReadsTheSingleResumeBeforeCollectingWithANonEmptyKeyword() {
        var result = run("帮我找找深圳的实习");
        assertEquals(List.of("list_resumes", "get_resume", "get_job_search_preferences", "collect_boss_jobs", "rank_jobs_for_resume"), calls);
        assertFalse(collections.getFirst().path("keyword").asText().isBlank());
        assertTrue(collections.getFirst().path("keyword").asText().contains("Java"));
        assertEquals("深圳", collections.getFirst().path("city").asText());
        assertEquals("在校生", collections.getFirst().path("experience").asText());
        assertEquals(1, rankings.getFirst().path("resumeId").asInt());
        assertEquals("[7]", rankings.getFirst().path("jobIds").toString());
        assertTrue(answer(result).contains("按你的《Java 后端简历》简历推断"));
    }

    @Test
    void emptyResultsTriggerOneAlternativeSearchAndStopWithoutInventingJobs() {
        jobs = List.of();
        var result = run("帮我找深圳实习");
        assertEquals(List.of("list_resumes", "get_resume", "get_job_search_preferences", "collect_boss_jobs", "collect_boss_jobs"), calls);
        assertEquals("Java 后端", collections.getFirst().path("keyword").asText());
        assertEquals("Java开发", collections.getLast().path("keyword").asText());
        assertEquals("在校生", collections.getLast().path("experience").asText());
        assertEquals("深圳", collections.getLast().path("city").asText());
        assertTrue(answer(result).contains("不能据此断定市场上没有实习"));
        assertTrue(rankings.isEmpty());
    }

    @Test
    void theBrowserReportResumesRankingWithTheSameSelectedResume() {
        var browser = new DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "浏览器采集"; }
            public ToolSchema parameters() { return tool("collect_boss_jobs").parameters(); }
            public Begin begin(JsonNode arguments, AgentToolContext context) {
                calls.add(name());
                collections.add(arguments);
                return new InBrowser("browser-extension", "collectBossJobs", Map.of("keyword", arguments.path("keyword").asText()));
            }
            public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) { return collectedData(); }
        };
        var user = new AppUser();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 9L);
        var context = new AgentToolContext(user);
        var registry = new AgentToolRegistry(List.of(tool("list_resumes"), tool("get_resume"), tool("get_job_search_preferences"), browser, tool("rank_jobs_for_resume")));
        var loop = new AgentLoop(new ScriptedAgentModel(mapper), registry, mapper, properties, System::currentTimeMillis);
        var sink = new AgentLoopTests.RecordingSink();
        var paused = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, context, sink, () -> false);
        assertEquals(AgentLoop.State.AWAITING_BROWSER, paused.state());
        var task = (AgentLoop.BrowserTaskEvent) sink.events.stream().filter(event -> event.name().equals("browser_task_required"))
                .findFirst().orElseThrow().payload();
        var report = new TranscriptPolicy.BrowserReport(task.toolCallId(), task.taskId(), "completed", mapper.createObjectNode());
        var normalized = new TranscriptPolicy(properties).normalize(paused.transcript(), task.toolCallId(), call -> "unused");
        var outcome = loop.run(normalized, null, context, new AgentLoopTests.RecordingSink(), () -> false,
                System.currentTimeMillis(), new AgentLoop.TurnOptions(null, List.of(), false, report));
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(List.of("list_resumes", "get_resume", "get_job_search_preferences", "collect_boss_jobs", "rank_jobs_for_resume"), calls);
        assertEquals(1, rankings.getFirst().path("resumeId").asInt());
        assertEquals("[7]", rankings.getFirst().path("jobIds").toString());
    }

    @Test
    void differentVersionsOfTheSameDirectionUseTheMostRecentlyUpdatedResume() {
        resumes = List.of(resume(1, "Java 后端 v1", "Java Spring Boot", "2026-10-20T10:00:00"),
                resume(2, "Java 后端 v2", "Java Spring Boot Redis", "2026-10-22T10:00:00"));
        var result = run("找深圳实习");
        assertEquals(2, rankings.getFirst().path("resumeId").asInt());
        assertTrue(answer(result).contains("Java 后端 v2"));
        assertEquals(1, calls.stream().filter("get_resume"::equals).count());
    }

    @Test
    void differentDirectionsArePresentedBeforeAnyCollection() {
        resumes = List.of(resume(1, "简历版本 A", "Java Spring Boot", "2026-10-22T10:00:00"),
                resume(2, "简历版本 B", "React Vue TypeScript 前端", "2026-10-21T10:00:00"));
        var result = run("找深圳实习");
        assertEquals(List.of("list_resumes"), calls);
        assertTrue(collections.isEmpty());
        assertTrue(answer(result).contains("Java 后端"));
        assertTrue(answer(result).contains("前端开发"));
        assertTrue(answer(result).contains("简历版本 A"));
        assertTrue(answer(result).contains("简历版本 B"));
    }

    @Test
    void aChosenDirectionCarriesAcrossLaterQuestionsWithoutAskingAgain() {
        jobs = List.of(Map.of("jobId", 7, "title", "前端开发实习生", "companyName", "示例科技"));
        resumes = List.of(resume(1, "Java 简历", "Java Spring Boot", "2026-10-22T10:00:00"),
                resume(2, "前端简历", "React Vue TypeScript", "2026-10-20T10:00:00"));
        var choice = run("找深圳实习");
        assertTrue(collections.isEmpty());
        var reply = new ArrayList<>(choice.transcript());
        reply.add(AgentMessage.user("前端"));
        var selected = loop().run(new TranscriptPolicy(properties).normalize(reply, null), null,
                new AgentToolContext(new AppUser()), (event, data) -> {}, () -> false);
        assertEquals(2, rankings.getLast().path("resumeId").asInt());
        assertEquals("深圳", collections.getLast().path("city").asText());
        assertEquals("在校生", collections.getLast().path("experience").asText());
        var continuation = new ArrayList<>(selected.transcript());
        continuation.add(AgentMessage.user("继续找深圳实习"));
        calls.clear();
        loop().run(new TranscriptPolicy(properties).normalize(continuation, null), null,
                new AgentToolContext(new AppUser()), (event, data) -> {}, () -> false);
        assertEquals(List.of("collect_boss_jobs", "rank_jobs_for_resume"), calls);
        assertEquals(2, rankings.getLast().path("resumeId").asInt());
    }

    @Test
    void anExplicitDirectionSelectsTheMatchingResumeInsteadOfTheNewestUnrelatedOne() {
        resumes = List.of(resume(1, "Java 简历", "Java Spring Boot", "2026-10-22T10:00:00"),
                resume(2, "前端简历", "React Vue TypeScript", "2026-10-20T10:00:00"));
        run("找深圳前端实习");
        assertEquals(2, rankings.getFirst().path("resumeId").asInt());
        assertTrue(collections.getFirst().path("keyword").asText().contains("前端"));
    }

    @Test
    void relevantJobsAreShownAheadOfAnUnrelatedMinority() {
        jobs = List.of(Map.of("jobId", 8, "title", "客服实习", "companyName", "示例科技"),
                Map.of("jobId", 7, "title", "Java 后端实习生", "companyName", "示例科技"));
        var result = run("找深圳实习");
        assertEquals(1, rankings.size());
        assertTrue(answer(result).contains("Java 后端实习生"));
        assertFalse(answer(result).contains("客服实习"));
    }

    @Test
    void anUnrelatedCustomerServiceBatchIsRankedButNotPresentedAsSuitable() {
        jobs = List.of(Map.of("jobId", 7, "title", "客服实习", "companyName", "示例科技"));
        var result = run("帮我找深圳实习");
        assertEquals(2, rankings.size());
        assertTrue(answer(result).contains("不对口"));
        assertTrue(answer(result).contains("不把这些岗位作为推荐"));
        assertEquals(2, collections.size(), "irrelevant results should trigger one bounded alternative search");
        assertEquals("Java开发", collections.getLast().path("keyword").asText());
    }

    @Test
    void abnormalStopsNeverTriggerAnotherKeywordSearch() {
        for (String reason : List.of("BOSS_NOT_LOGGED_IN", "CAPTCHA", "USER_CANCELLED", "STRUCTURE_CHANGED")) {
            calls.clear();
            collections.clear();
            rankings.clear();
            stopReason = reason;
            finishReason = null;
            var result = run("帮我找深圳实习");
            assertEquals(1, collections.size(), reason);
            assertTrue(answer(result).contains(reason), reason);
        }
    }

    @Test
    void aFirstPageNoMoreResultsHaltsAsSuspectedLoginProblem() {
        finishReason = "NO_MORE_RESULTS";
        pagesRead = 1;
        run("帮我找深圳实习");
        assertEquals(1, collections.size());
        assertTrue(rankings.isEmpty(), "suspected login problems must not trigger another browser call");
    }

    @Test
    void noMoreResultsAfterMultiplePagesMayStillReplanWithAnotherKeyword() {
        jobs = List.of();
        finishReason = "NO_MORE_RESULTS";
        pagesRead = 2;
        run("帮我找深圳实习");
        assertEquals(2, collections.size());
    }

    @Test
    void reachingDailyLimitDoesNotTriggerAnotherSearch() {
        jobs = List.of();
        finishReason = "DAILY_LIMIT";
        run("帮我找深圳实习");
        assertEquals(1, collections.size());
    }

    private AgentLoop.Outcome run(String question) {
        return loop().run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user(question)), null),
                null, new AgentToolContext(new AppUser()), (event, data) -> {}, () -> false);
    }

    private AgentLoop loop() {
        var registry = new AgentToolRegistry(List.of(tool("list_resumes"), tool("get_resume"), tool("get_job_search_preferences"),
                tool("collect_boss_jobs"), tool("rank_jobs_for_resume")));
        return new AgentLoop(new ScriptedAgentModel(mapper), registry, mapper, properties, System::currentTimeMillis);
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            public String name() { return name; }
            public String description() { return name; }
            public ToolSchema parameters() {
                return switch (name) {
                    case "list_resumes" -> ToolSchema.object().integer("page", "页码", false, 0, 20);
                    case "get_resume" -> ToolSchema.object().integer("resumeId", "简历", true, 1, 100);
                    case "get_job_search_preferences" -> ToolSchema.object();
                    case "collect_boss_jobs" -> ToolSchema.object().string("keyword", "关键词", true, 50)
                            .string("city", "城市", true, 10).string("experience", "经验", false, 10)
                            .integer("maxPages", "页数", false, 1, 3);
                    default -> ToolSchema.object().integer("resumeId", "简历", true, 1, 100)
                            .integer("limit", "条数", false, 1, 10).integerArray("jobIds", "岗位", true, 1, 100, 45);
                };
            }
            public Object execute(JsonNode arguments, AgentToolContext context) {
                calls.add(name);
                return switch (name) {
                    case "list_resumes" -> Map.of("total", resumes.size(), "page", 0, "totalPages", 1, "resumes", resumes);
                    case "get_job_search_preferences" -> Map.of("configured", true);
                    case "get_resume" -> {
                        var row = resumes.stream().filter(r -> ((Number) r.get("resumeId")).longValue() == arguments.path("resumeId").asLong())
                                .findFirst().orElseThrow();
                        yield Map.of("resumeId", row.get("resumeId"), "title", row.get("title"), "text", row.get("directionEvidence"));
                    }
                    case "collect_boss_jobs" -> {
                        collections.add(arguments);
                        yield collectedData();
                    }
                    default -> {
                        rankings.add(arguments);
                        yield Map.of("resumeId", arguments.path("resumeId").asLong(), "matches", jobs);
                    }
                };
            }
        };
    }

    private Object collectedData() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("jobs", jobs);
        data.put("jobIds", jobs.stream().map(j -> j.get("jobId")).toList());
        data.put("collected", jobs.size());
        data.put("pagesRead", pagesRead);
        data.put("created", 0);
        data.put("alreadyInLibrary", jobs.size());
        data.put("status", stopReason == null ? "COMPLETED" : "STOPPED");
        if (finishReason != null) data.put("finishReason", finishReason);
        if (stopReason != null) data.put("stopReason", stopReason);
        return data;
    }

    private static Map<String, Object> resume(int id, String title, String evidence, String date) {
        return Map.of("resumeId", id, "title", title, "directionEvidence", evidence, "updatedAt", date);
    }
    private static String answer(AgentLoop.Outcome result) { return result.transcript().getLast().content(); }
}
