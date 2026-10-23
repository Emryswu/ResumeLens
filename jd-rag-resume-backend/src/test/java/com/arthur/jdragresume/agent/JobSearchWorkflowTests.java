package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobSearchWorkflowTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void countsRequestedPagesAndUniqueJobsAcrossBrowserResultsWithoutClaimingVerification() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        add(log, "a", "Java 后端", 2, "{\"pagesRead\":2,\"jobIds\":[1,2],\"dailyPagesLeft\":27}");
        add(log, "b", "Java开发", 1, "{\"pagesRead\":1,\"jobIds\":[2,3],\"dailyPagesLeft\":26}");

        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(2, progress.attempts().size());
        assertEquals(3, progress.requestedPages());
        assertEquals(3, progress.pagesRead());
        assertEquals(3, progress.uniqueJobs());
        assertEquals(26, progress.dailyPagesLeft());
        assertTrue(JobSearchWorkflow.guidance(progress).contains("未经完整 JD 核实"));
        assertNull(JobSearchWorkflow.refuse(progress,
                mapper.readTree("{\"keyword\":\"AI 应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}")));
    }

    @Test
    void rejectsRepeatedSearchAndExcessivePageRequests() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java后端", 3, "{\"pagesRead\":0,\"jobIds\":[]}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"Java后端\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}")).contains("相同"));
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":3}")) == null);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":4}")).contains("最多请求"));
    }

    @Test
    void enforcesSearchCountAndDailyPageBudget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java后端", 1, "{\"pagesRead\":1,\"jobIds\":[1],\"dailyPagesLeft\":1}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":2}")).contains("每日"));
        add(log, "b", "Java开发", 1, "{\"pagesRead\":1,\"jobIds\":[2]}");
        add(log, "c", "后端开发", 1, "{\"pagesRead\":1,\"jobIds\":[3]}");
        assertTrue(JobSearchWorkflow.refuse(JobSearchWorkflow.inspect(log, mapper),
                mapper.readTree("{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"maxPages\":1}")).contains("次数"));
    }

    @Test
    void aNewQuestionStartsAFreshBudgetAndAnUnfinishedCallAlreadyConsumesOneAttempt() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java后端", 3, "{\"pagesRead\":1,\"jobIds\":[1]}");
        log.add(AgentMessage.user("再找一次北京实习"));
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("b", "collect_boss_jobs",
                "{\"keyword\":\"AI应用\",\"city\":\"北京\",\"maxPages\":2}"))));
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(1, progress.attempts().size());
        assertEquals(2, progress.requestedPages());
        assertEquals(0, progress.pagesRead());
        assertEquals(0, JobSearchWorkflow.inspect(log, mapper, "b").attempts().size());
    }

    @Test
    void agentLoopReallyRejectsTheFourthCollectionBeforeTheToolRuns() {
        var properties = new AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        AtomicInteger collections = new AtomicInteger();
        DeferrableTool collector = new DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "test"; }
            public ToolSchema parameters() {
                return ToolSchema.object().string("keyword", "keyword", true, 50)
                        .string("city", "city", true, 10).string("experience", "experience", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(JsonNode arguments, AgentToolContext context) {
                int index = collections.incrementAndGet();
                return new Done(Map.of("pagesRead", 1, "jobIds", List.of(index), "dailyPagesLeft", 20));
            }
            public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) { return Map.of(); }
        };
        AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results == 4) return AgentModel.Reply.answer("已达到预算，按已有候选说明不足");
            String keyword = switch ((int) results) {
                case 0 -> "Java后端";
                case 1 -> "Java开发";
                case 2 -> "AI应用";
                default -> "Spring Boot";
            };
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("c" + (results + 1), "collect_boss_jobs",
                    "{\"keyword\":\"" + keyword + "\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}"));
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(collector)), mapper, properties,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertEquals(3, collections.get());
        var failure = mapper.valueToTree(result.transcript().stream()
                .filter(m -> AgentMessage.TOOL.equals(m.role())).toList()).path(3).path("content").asText();
        assertEquals("JOB_SEARCH_BUDGET", mapper.readTree(failure).path("error").path("code").asText());
    }

    private void add(List<AgentMessage> log, String id, String keyword, int pages, String data) throws Exception {
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(id, "collect_boss_jobs",
                "{\"keyword\":\"" + keyword + "\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":" + pages + "}"))));
        log.add(AgentMessage.tool(id, "{\"tool\":\"collect_boss_jobs\",\"ok\":true,\"untrusted_data\":" + data + "}"));
    }
}
