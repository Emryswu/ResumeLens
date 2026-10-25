package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobSearchStopPolicyTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void invalidCollectionParametersDoNotHaltTheSearch() throws Exception {
        for (String code : List.of("BOSS_DIRECTION_REQUIRED", "INVALID_ARGUMENTS", "INVALID_COMMAND",
                "JOB_SEARCH_PREREQUISITES")) {
            var result = error(code);
            assertNull(JobSearchStopPolicy.haltReason(result), code);
            var messages = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
            messages.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                    "rejected", "collect_boss_jobs", "{\"keyword\":\"\",\"city\":\"深圳\",\"maxPages\":1}"))));
            messages.add(AgentMessage.tool("rejected", result.toString()));
            var progress = JobSearchWorkflow.inspect(messages, mapper);
            assertNull(progress.haltReason(), code);
            assertEquals(0, progress.attempts().size(), code + " did not open the browser");
            assertEquals(0, progress.requestedPages(), code);
            assertNull(JobSearchWorkflow.refuse(progress, mapper.readTree(
                    "{\"keyword\":\"Java 后端\",\"city\":\"深圳\",\"maxPages\":1}")), code);
        }
        assertNull(JobSearchStopPolicy.haltReason(error("TOOL_FAILED")),
                "unspecified failures are not proof of a persistent platform block");
    }

    @Test
    void knownPlatformOrEnvironmentErrorsStillHaltCollection() throws Exception {
        for (String code : List.of(
                "EXTENSION_NOT_FOUND", "EXTENSION_OUTDATED", "EXTENSION_RELOADED", "WORKER_UNREACHABLE",
                "PERMISSION_REQUIRED", "BOSS_NOT_LOGGED_IN", "CAPTCHA", "STRUCTURE_CHANGED",
                "PAGE_LOAD_TIMEOUT", "TAB_CLOSED", "UNEXPECTED_PAGE", "WORKER_RESTARTED",
                "USER_CANCELLED", "DAILY_LIMIT", "INTERNAL_ERROR", "BUSY",
                "PATCHRIGHT_UNAVAILABLE", "PATCHRIGHT_TIMEOUT", "PATCHRIGHT_INTERRUPTED",
                "BROWSER_TASK_EXPIRED", "BROWSER_TASK_CANCELLED", "BROWSER_TASK_UNKNOWN",
                "BROWSER_REPORT_INVALID")) {
            JsonNode error = error(code);
            assertNotNull(JobSearchStopPolicy.haltReason(error), code);
            var messages = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
            messages.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                    "broken", "collect_boss_jobs",
                    "{\"keyword\":\"Java\",\"city\":\"深圳\",\"maxPages\":1}"))));
            messages.add(AgentMessage.tool("broken", error.toString()));
            var progress = JobSearchWorkflow.inspect(messages, mapper);
            assertNotNull(progress.haltReason(), code);
            assertNotNull(JobSearchWorkflow.refuse(progress, mapper.readTree(
                    "{\"keyword\":\"Java开发\",\"city\":\"深圳\",\"maxPages\":1}")), code);
        }
    }

    @Test
    void emptyKeywordCanBeFixedAndCollectedInTheSameAgentTurn() throws Exception {
        var properties = new AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        var completed = new AtomicInteger();

        AgentTool resume = new AgentTool() {
            public String name() { return "get_resume"; }
            public String description() { return "read resume"; }
            public ToolSchema parameters() {
                return ToolSchema.object().integer("resumeId", "id", true, 1, 100);
            }
            public Object execute(JsonNode args, AgentToolContext context) {
                return Map.of("resumeId", 7, "text", "Java Spring Boot");
            }
        };
        AgentTool preferences = new AgentTool() {
            public String name() { return "get_job_search_preferences"; }
            public String description() { return "read preferences"; }
            public ToolSchema parameters() { return ToolSchema.object(); }
            public Object execute(JsonNode args, AgentToolContext context) {
                return Map.of("configured", true);
            }
        };
        DeferrableTool collector = new DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "collect"; }
            public ToolSchema parameters() {
                return ToolSchema.object().string("keyword", "keyword", false, 50)
                        .string("city", "city", true, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(JsonNode args, AgentToolContext context) {
                if (args.path("keyword").asText("").isBlank()) {
                    throw new BusinessException("BOSS_DIRECTION_REQUIRED",
                            "Specify keyword or explicitly enable broadSearch");
                }
                completed.incrementAndGet();
                return new Done(Map.of("pagesRead", 1, "jobIds", List.of(7L),
                        "finishReason", "MAX_PAGES", "status", "COMPLETED", "dailyPagesLeft", 20));
            }
            public Object resume(JsonNode args, JsonNode report, AgentToolContext context) {
                throw new AssertionError("the test must finish on the server");
            }
        };
        AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            return switch ((int) results) {
                case 0 -> AgentModel.Reply.call(AgentMessage.ToolCall.of(
                        "resume", "get_resume", "{\"resumeId\":7}"));
                case 1 -> AgentModel.Reply.call(AgentMessage.ToolCall.of(
                        "preferences", "get_job_search_preferences", "{}"));
                case 2 -> AgentModel.Reply.call(AgentMessage.ToolCall.of(
                        "empty", "collect_boss_jobs", "{\"keyword\":\"\",\"city\":\"深圳\",\"maxPages\":1}"));
                case 3 -> AgentModel.Reply.call(AgentMessage.ToolCall.of(
                        "corrected", "collect_boss_jobs",
                        "{\"keyword\":\"Java 后端\",\"city\":\"深圳\",\"maxPages\":1}"));
                default -> AgentModel.Reply.answer("已成功采集 Java 岗位");
            };
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(resume, preferences, collector)),
                mapper, properties, System::currentTimeMillis);
        var outcome = loop.run(new TranscriptPolicy(properties).normalize(
                        List.of(AgentMessage.user("帮我找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (event, data) -> {}, () -> false);

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, completed.get(), "corrected collection must execute after validation error");
        var toolResults = outcome.transcript().stream()
                .filter(m -> AgentMessage.TOOL.equals(m.role())).toList();
        assertEquals("BOSS_DIRECTION_REQUIRED", mapper.readTree(toolResults.get(2).content())
                .path("error").path("code").asText());
        assertTrue(mapper.readTree(toolResults.get(3).content()).path("ok").asBoolean());
        var progress = JobSearchWorkflow.inspect(outcome.transcript(), mapper);
        assertNull(progress.haltReason());
        assertEquals(1, progress.attempts().size(), "invalid request never opened a browser");
        assertEquals(1, progress.requestedPages());
        assertEquals(1, progress.pagesRead());
        assertEquals(1, progress.uniqueJobs());
    }

    @Test
    void everyBrowserAndPatchrightErrorCodeIsClassified() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        java.util.List<java.nio.file.Path> sources = new java.util.ArrayList<>();
        for (String directory : java.util.List.of("browser-extension/src", "collector-service/src")) {
            java.nio.file.Path path = root.resolve(directory);
            assertTrue(java.nio.file.Files.isDirectory(path), "error-code scanner missing source directory: " + path);
            try (var files = java.nio.file.Files.walk(path)) {
                sources.addAll(files.filter(file -> file.toString().endsWith(".js")
                        || file.toString().endsWith(".mjs")).toList());
            }
        }
        java.nio.file.Path patchright = root.resolve(
                "jd-rag-resume-backend/src/main/java/com/arthur/jdragresume/collect/PatchrightJobCollector.java");
        assertTrue(java.nio.file.Files.isRegularFile(patchright));
        sources.add(patchright);

        var javascriptCodes = java.util.regex.Pattern.compile(
                "(?:new\\s+CollectStop\\s*\\(|\\bcode\\s*[:=]\\s*|\\breturn\\s+)"
                        + "\\s*[\\\"']([A-Z][A-Z0-9_]+)[\\\"']");
        var javaCodes = java.util.regex.Pattern.compile(
                "new\\s+BusinessException\\s*\\(\\s*\\\"([A-Z][A-Z0-9_]+)\\\"");
        java.util.Set<String> discovered = new java.util.TreeSet<>();
        java.util.List<String> unclassified = new java.util.ArrayList<>();
        for (var source : sources) {
            String text = java.nio.file.Files.readString(source);
            var matches = (source.equals(patchright) ? javaCodes : javascriptCodes).matcher(text);
            while (matches.find()) {
                String code = matches.group(1);
                discovered.add(code);
                if (!JobSearchStopPolicy.PLATFORM_BLOCKING_ERRORS.contains(code)
                        && !JobSearchStopPolicy.CORRECTABLE_ERRORS.contains(code)) {
                    unclassified.add(root.relativize(source) + ": " + code);
                }
            }
        }
        assertFalse(discovered.isEmpty(), "source-code scan must discover actual browser error codes");
        assertTrue(unclassified.isEmpty(), "unclassified browser/Patchright error codes: " + unclassified);
    }

    private JsonNode error(String code) throws Exception {
        return mapper.readTree("{\"tool\":\"collect_boss_jobs\",\"ok\":false,\"error\":{\"code\":\""
                + code + "\",\"message\":\"test\"}}");
    }
}
