package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.ResourceNotFoundException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ConversationStoreTests {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("conversation_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");
    ConversationStore store;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("14").load().migrate();
        Flyway.configure().dataSource(source).target("22").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at,display_name,email,password_hash,username) VALUES"
                + "(1,NOW(),NOW(),'Alice','alice@test.local','hash','alice'),(2,NOW(),NOW(),'Bob','bob@test.local','hash','bob')");
        store = new ConversationStore(jdbc, new DataSourceTransactionManager(source));
    }

    @Test
    void modelReceivesRedactedCopiesButDatabaseTranscriptAndEventsKeepOriginals() throws Exception {
        String raw = "13812345678 arthur@example.com";
        var mapper = ConversationStore.JSON;
        var properties = new AgentProperties();
        var policy = new TranscriptPolicy(properties);
        long id = store.create(1).conversation().id();
        var run = store.accept(1, id, raw, null, null, 90000, messages -> policy.normalize(messages, null));
        var messages = new java.util.ArrayList<>(run.input().messages());
        messages.add(AgentMessage.assistant(null, java.util.List.of(AgentMessage.ToolCall.of("c1", "get_resume", "{\"resumeId\":1}"))));
        messages.add(AgentMessage.tool("c1", "简历正文/BOSS JD：" + raw));
        store.checkpoint(run, messages, raw);
        store.emit(run, "step", java.util.Map.of("resultPreview", raw));
        var client = org.mockito.Mockito.mock(com.arthur.jdragresume.ai.AiClient.class);
        org.mockito.Mockito.when(client.chatWithTools(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    String request = mapper.writeValueAsString(inv.getArgument(0));
                    assertFalse(request.contains("13812345678"));
                    assertFalse(request.contains("arthur@example.com"));
                    assertTrue(request.contains("[手机号] [邮箱]"));
                    return mapper.readTree("{\"content\":\"ok\"}");
                });
        new OpenAiCompatibleAgentModel(client).next(raw, store.messages(id), java.util.List.of(), java.time.Duration.ofSeconds(5));
        assertEquals(messages, store.messages(id));
        assertEquals(raw, jdbc.queryForObject("SELECT summary FROM agent_conversation WHERE id=?", String.class, id));
        assertTrue(store.detail(1, id, 0).events().stream().anyMatch(e -> e.type().equals("step")
                && e.data().path("resultPreview").asText().equals(raw)));
        org.mockito.Mockito.verify(client).chatWithTools(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ownershipAndCascadeApplyToConversationMessagesAndEvents() {
        long alice = store.create(1).conversation().id();
        long bob = store.create(2).conversation().id();
        assertEquals(1, store.list(1).size());
        assertThrows(ResourceNotFoundException.class, () -> store.detail(1, bob, 0));
        assertThrows(ResourceNotFoundException.class, () -> store.delete(1, bob));
        jdbc.update("INSERT INTO agent_conversation_message VALUES(?,0,?)", alice, "{\"role\":\"user\",\"content\":\"你好\"}");
        event(alice, 1, "ask", "{\"question\":\"你好\"}");
        jdbc.update("DELETE FROM app_user WHERE id=1");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_event", Integer.class));
        assertEquals(bob, store.list(2).getFirst().id());
    }

    @Test
    void fullAccessIsStoredOnTheConversationAndOnlyItsOwnerCanChangeIt() {
        long alice = store.create(1).conversation().id();
        assertFalse(store.detail(1, alice, 0).conversation().fullAccess(), "off until the user turns it on");
        assertThrows(ResourceNotFoundException.class, () -> store.setFullAccess(2, alice, true));
        assertFalse(store.list(1).getFirst().fullAccess());

        assertTrue(store.setFullAccess(1, alice, true).fullAccess());
        assertTrue(store.detail(1, alice, 0).conversation().fullAccess(), "a reload reads it back from the server");
        var run = store.accept(1, alice, "找岗位", null, null, 90000,
                messages -> new TranscriptPolicy(new AgentProperties()).normalize(messages, null));
        assertTrue(store.fullAccess(run));

        assertFalse(store.setFullAccess(1, alice, false).fullAccess());
        assertFalse(store.fullAccess(run), "turning it off applies to the next browser task of a running turn");
        assertFalse(store.create(1).conversation().fullAccess(), "a new conversation starts with it off");
    }

    @Test
    void projectsEveryCardAndKeepsCursorIndependentOfFullTurns() {
        long id = store.create(1).conversation().id();
        event(id, 1, "ask", "{\"question\":\"分析\"}");
        event(id, 2, "note", "{\"content\":\"先查简历\"}");
        event(id, 3, "confirmation_required", "{\"toolCallId\":\"a\",\"tool\":\"start_analysis\",\"arguments\":{}}");
        event(id, 4, "done", "{\"state\":\"AWAITING_CONFIRMATION\"}");
        event(id, 5, "decide", "{\"approved\":true,\"toolCallId\":\"a\"}");
        event(id, 6, "step_started", "{\"toolCallId\":\"a\",\"tool\":\"start_analysis\",\"arguments\":\"{}\"}");
        event(id, 7, "step", "{\"toolCallId\":\"a\",\"tool\":\"start_analysis\",\"ok\":true,\"resultPreview\":\"{\\\"analysisId\\\":7}\"}");
        event(id, 8, "browser_task_required", "{\"taskId\":\"b\",\"expiresAt\":1000}");
        event(id, 9, "message", "{\"content\":\"完成\"}");
        event(id, 10, "done", "{\"state\":\"COMPLETED\"}");
        var detail = store.detail(1, id, 7);
        assertEquals(3, detail.events().size());
        var turn = detail.turns().get(0);
        assertEquals("先查简历", turn.path("notes").get(0).asText());
        assertEquals("approved", turn.path("decision").asText());
        assertEquals(7, turn.path("analysisId").asLong());
        assertEquals("b", turn.path("browserTask").path("taskId").asText());
        assertEquals("完成", turn.path("answer").asText());
        assertTrue(turn.path("unfinished").isEmpty());
        store.delete(1, id);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_event", Integer.class));
    }

    @Test
    void anOlderConversationWithNewActivitySortsAheadOfANewerEmptyConversation() {
        long older = store.create(1).conversation().id();
        long newer = store.create(1).conversation().id();
        assertEquals(newer, store.list(1).getFirst().id());
        var policy = new TranscriptPolicy(new AgentProperties());
        var run = store.accept(1, older, "新的问题", null, null, 90000, messages -> policy.normalize(messages, null));
        store.emit(run, "message", java.util.Map.of("content", "新的回答"));
        store.emit(run, "done", java.util.Map.of("state", "COMPLETED", "steps", 1));
        assertEquals(older, store.list(1).getFirst().id());
        var header = store.detail(1, older, 0).conversation();
        assertFalse(java.time.Instant.parse(header.updatedAt()).isBefore(java.time.Instant.parse(header.createdAt())));
    }

    @Test
    void answeringAConfirmationContinuesTheTurnWithoutAddingAUserMessage() {
        // Plan state and spent budgets replay from the last user message; a decision must not start a new turn.
        var policy = new TranscriptPolicy(new AgentProperties());
        for (boolean approved : new boolean[] {true, false}) {
            long id = store.create(1).conversation().id();
            var run = store.accept(1, id, "帮我做完整分析", null, null, 90000, messages -> policy.normalize(messages, null));
            store.checkpoint(run, java.util.List.of(AgentMessage.user("帮我做完整分析"), AgentMessage.assistant(null,
                    java.util.List.of(AgentMessage.ToolCall.of("write", "start_analysis", "{}")))), null);
            store.emit(run, "done", java.util.Map.of("state", "AWAITING_CONFIRMATION", "steps", 1));

            var approval = new TranscriptPolicy.Approval("write", approved);
            var resumed = store.accept(1, id, null, approval, null, 90000, messages -> policy.normalize(messages, approval));
            assertEquals(run.turnId(), resumed.turnId());
            assertEquals("write", resumed.input().pending().id());
            assertEquals(1, resumed.input().messages().stream().filter(message -> AgentMessage.USER.equals(message.role())).count());
            assertEquals(1, store.messages(id).stream().filter(message -> AgentMessage.USER.equals(message.role())).count());
        }
    }

    @Test
    void searchConditionsAreDurableAndScopedToUserAndConversation() {
        long a = store.create(1).conversation().id();
        long b = store.create(1).conversation().id();
        long foreign = store.create(2).conversation().id();
        var policy = new TranscriptPolicy(new AgentProperties());
        var run = store.accept(1, a, "找深圳 Java 实习", null, null, 90000,
                messages -> policy.normalize(messages, null));
        var saved = store.updateSearchContext(run, old -> old.patch(
                ConversationStore.JSON.createObjectNode()
                        .put("city", "深圳").put("keyword", "Java").put("jobType", "实习"))
                .selectedResume(7).preferencesRead());
        store.updateSearchContext(run, old -> old.collected(saved.asSearch()));
        store.emit(run, "done", java.util.Map.of("state", "COMPLETED", "steps", 1));

        var second = store.accept(1, a, "再找几个", null, null, 90000,
                messages -> policy.normalize(messages, null));
        var restored = store.searchContext(second);
        assertEquals("深圳", restored.city());
        assertEquals("实习", restored.jobType());
        assertEquals(7L, restored.resumeId());
        assertTrue(restored.preferencesLoaded());
        assertEquals(1, restored.searched().size());
        assertTrue(restored.alreadySearched(restored.asSearch()));
        assertThrows(ResourceNotFoundException.class, () -> store.detail(2, a, 0));
        assertThrows(ResourceNotFoundException.class, () -> store.detail(1, foreign, 0));
        assertThrows(ResourceNotFoundException.class, () -> store.updateSearchContext(
                new ConversationStore.Run(2, a, second.turnId(), second.runId(), null, null, 0),
                old -> old));
        var other = store.accept(1, b, "你好", null, null, 90000,
                messages -> policy.normalize(messages, null));
        assertFalse(store.searchContext(other).hasConditions(), "another conversation must not inherit search state");
    }

    @Test
    void aUserApprovedRelaxationIsStoredAndInheritedByTheNextTurnWhileTheUpdateToolCannotForgeIt() {
        long conversationId = store.create(1).conversation().id();
        var policy = new TranscriptPolicy(new AgentProperties());
        var mapper = ConversationStore.JSON;
        var user = new com.arthur.jdragresume.entity.AppUser();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        JobSearchProfile student = userId -> true;
        var first = store.accept(1, conversationId, "找杭州实习", null, null, 90000,
                messages -> policy.normalize(messages, null));
        store.updateSearchContext(first, old -> old.patch(mapper.createObjectNode().put("jobType", "实习").put("city", "杭州")));
        var context = new com.arthur.jdragresume.agent.tool.AgentToolContext(user, first);

        var update = new com.arthur.jdragresume.agent.tool.UpdateJobSearchContextTool(store, student);
        assertThrows(com.arthur.jdragresume.exception.ToolNotStartedException.class,
                () -> update.execute(mapper.createObjectNode().put("experience", "不限"), context));
        assertNull(store.searchContext(first).experience(), "a refused update leaves the stored row unchanged");

        new com.arthur.jdragresume.agent.tool.RelaxJobSearchConditionTool(store, student)
                .execute(mapper.createObjectNode().put("experience", "不限"), context);
        store.emit(first, "done", java.util.Map.of("state", "COMPLETED", "steps", 1));

        var second = store.accept(1, conversationId, "再找找", null, null, 90000,
                messages -> policy.normalize(messages, null));
        var restored = store.searchContext(second);
        assertEquals("不限", restored.experience());
        assertTrue(restored.userConfirmed().contains("experience"));
        assertTrue(SearchConditionPolicy.conflict(restored, true).isEmpty(), "the approval is not re-imposed next turn");
    }

    @Test
    void agentLoopCollectsInheritedFiltersAndBlocksRepeatBeforeBrowser() {
        long conversationId = store.create(1).conversation().id();
        var policy = new TranscriptPolicy(new AgentProperties());
        var executed = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var callNumbers = new java.util.concurrent.atomic.AtomicInteger();
        var unrelatedSeen = new java.util.concurrent.atomic.AtomicBoolean();
        var mapper = ConversationStore.JSON;

        com.arthur.jdragresume.agent.tool.DeferrableTool collector = new com.arthur.jdragresume.agent.tool.DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "collect"; }
            public com.arthur.jdragresume.agent.tool.ToolSchema parameters() {
                return com.arthur.jdragresume.agent.tool.ToolSchema.object()
                        .string("keyword", "keyword", false, 50)
                        .string("city", "city", false, 10)
                        .string("jobType", "type", false, 10)
                        .string("experience", "experience", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(com.fasterxml.jackson.databind.JsonNode args,
                               com.arthur.jdragresume.agent.tool.AgentToolContext context) {
                executed.add(args.deepCopy());
                return new Done(java.util.Map.of("pagesRead", 1,
                        "jobIds", java.util.List.of((long) executed.size()), "dailyPagesLeft", 20));
            }
            public Object resume(com.fasterxml.jackson.databind.JsonNode args,
                                 com.fasterxml.jackson.databind.JsonNode report,
                                 com.arthur.jdragresume.agent.tool.AgentToolContext context) {
                throw new AssertionError("collection should finish without browser delegation");
            }
        };

        AgentModel model = (system, messages, tools, timeout) -> {
            int lastUser = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (AgentMessage.USER.equals(messages.get(i).role())) lastUser = i;
            }
            String question = messages.get(lastUser).content();
            if ("解释一下 Java 的 volatile".equals(question)) {
                assertTrue(system.contains("本会话保存的岗位搜索条件"));
                assertFalse(system.contains("求职搜索策略"), "ordinary chat must not inherit full workflow guidance");
                assertFalse(system.contains("BOSS 采集正常结束后"), "stop policy must only be injected into job searches");
                unrelatedSeen.set(true);
                return AgentModel.Reply.answer("volatile 保证线程间可见性");
            }
            if (messages.subList(lastUser + 1, messages.size()).stream()
                    .anyMatch(message -> AgentMessage.TOOL.equals(message.role()))) {
                return AgentModel.Reply.answer("已处理");
            }
            var args = mapper.createObjectNode();
            switch (question) {
                case "找深圳 Java 实习" -> args.put("keyword", "Java").put("city", "深圳").put("jobType", "实习");
                case "再找几个（换方向）" -> args.put("keyword", "Spring Boot");
                case "再找几个" -> { }
                case "看看全职的" -> args.put("jobType", "全职");
                case "清空城市" -> args.put("city", "");
                default -> throw new AssertionError("unexpected user message " + question);
            }
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("call-" + callNumbers.incrementAndGet(),
                    "collect_boss_jobs", args.toString()));
        };
        var properties = new AgentProperties();
        var loop = new AgentLoop(model,
                new com.arthur.jdragresume.agent.tool.AgentToolRegistry(java.util.List.of(collector)),
                mapper, properties, System::currentTimeMillis, new BrowserTaskRegistry(), store);

        var first = store.accept(1, conversationId, "找深圳 Java 实习", null, null, 90000,
                messages -> policy.normalize(messages, null));
        store.updateSearchContext(first, old -> old.selectedResume(7).preferencesRead());
        runSearchTurn(loop, first);
        assertEquals(1, executed.size());
        assertEquals("深圳", executed.get(0).path("city").asText());
        assertEquals("在校生", executed.get(0).path("experience").asText());

        var second = store.accept(1, conversationId, "再找几个（换方向）", null, null, 90000,
                messages -> policy.normalize(messages, null));
        runSearchTurn(loop, second);
        assertEquals(2, executed.size());
        assertEquals("Spring Boot", executed.get(1).path("keyword").asText());
        assertEquals("深圳", executed.get(1).path("city").asText());
        assertEquals("在校生", executed.get(1).path("experience").asText());

        var third = store.accept(1, conversationId, "再找几个", null, null, 90000,
                messages -> policy.normalize(messages, null));
        var duplicate = runSearchTurn(loop, third);
        assertEquals(2, executed.size(), "repeated search never reaches the collector");
        assertTrue(duplicate.transcript().stream().filter(m -> AgentMessage.TOOL.equals(m.role()))
                .anyMatch(m -> m.content().contains("JOB_SEARCH_REPEAT")));

        var fourth = store.accept(1, conversationId, "看看全职的", null, null, 90000,
                messages -> policy.normalize(messages, null));
        runSearchTurn(loop, fourth);
        assertEquals(3, executed.size());
        assertEquals("不限", executed.get(2).path("experience").asText());
        assertEquals("深圳", executed.get(2).path("city").asText());

        var fifth = store.accept(1, conversationId, "清空城市", null, null, 90000,
                messages -> policy.normalize(messages, null));
        runSearchTurn(loop, fifth);
        assertEquals(4, executed.size());
        assertEquals("全国", executed.get(3).path("city").asText());
        var unrelated = store.accept(1, conversationId, "解释一下 Java 的 volatile", null, null, 90000,
                messages -> policy.normalize(messages, null));
        runSearchTurn(loop, unrelated);
        assertTrue(unrelatedSeen.get());
        assertEquals(4, executed.size(), "unrelated questions must not trigger collection");

        long freshId = store.create(1).conversation().id();
        assertNull(jdbc.queryForObject("SELECT search_context FROM agent_conversation WHERE id=?",
                String.class, freshId), "a new conversation cannot inherit the previous campaign");
    }

    @Test
    void anInvalidStoredConditionIsRefusedBeforeExecutionAndTheCorrectedCallStillCollects() throws Exception {
        long conversationId = store.create(1).conversation().id();
        var policy = new TranscriptPolicy(new AgentProperties());
        var mapper = ConversationStore.JSON;
        var collected = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        com.arthur.jdragresume.agent.tool.DeferrableTool collector = new com.arthur.jdragresume.agent.tool.DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "collect"; }
            public com.arthur.jdragresume.agent.tool.ToolSchema parameters() {
                return com.arthur.jdragresume.agent.tool.ToolSchema.object()
                        .string("keyword", "keyword", true, 50)
                        .string("city", "city", true, 10)
                        .string("jobType", "type", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(com.fasterxml.jackson.databind.JsonNode args,
                               com.arthur.jdragresume.agent.tool.AgentToolContext context) {
                collected.add(args);
                return new Done(java.util.Map.of("status", "COMPLETED", "finishReason", "MAX_PAGES",
                        "pagesRead", 1, "jobIds", java.util.List.of(11L), "dailyPagesLeft", 20));
            }
            public Object resume(com.fasterxml.jackson.databind.JsonNode args,
                                 com.fasterxml.jackson.databind.JsonNode report,
                                 com.arthur.jdragresume.agent.tool.AgentToolContext context) {
                throw new AssertionError("collection finishes on the server");
            }
        };
        // The schema accepts any short jobType; only the stored session conditions know "兼职" is not allowed.
        AgentModel model = (system, messages, tools, timeout) -> {
            long results = messages.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results == 0) return AgentModel.Reply.call(AgentMessage.ToolCall.of("invalid", "collect_boss_jobs",
                    "{\"keyword\":\"Java\",\"city\":\"深圳\",\"jobType\":\"兼职\"}"));
            if (results == 1) return AgentModel.Reply.call(AgentMessage.ToolCall.of("corrected", "collect_boss_jobs",
                    "{\"keyword\":\"Java\",\"city\":\"深圳\",\"jobType\":\"实习\"}"));
            return AgentModel.Reply.answer("已采集");
        };
        var loop = new AgentLoop(model,
                new com.arthur.jdragresume.agent.tool.AgentToolRegistry(java.util.List.of(collector)),
                mapper, new AgentProperties(), System::currentTimeMillis, new BrowserTaskRegistry(store), store);
        var run = store.accept(1, conversationId, "找深圳 Java 实习", null, null, 90000,
                messages -> policy.normalize(messages, null));
        store.updateSearchContext(run, old -> old.selectedResume(7).preferencesRead());

        var outcome = runSearchTurn(loop, run);

        var results = outcome.transcript().stream().filter(m -> AgentMessage.TOOL.equals(m.role())).toList();
        var refused = mapper.readTree(results.get(0).content());
        assertEquals("INVALID_ARGUMENTS", refused.path("error").path("code").asText());
        assertFalse(ToolEnvelope.executed(refused), "the invalid jobType never reached the collector");
        assertTrue(mapper.readTree(results.get(1).content()).path("ok").asBoolean(), results.get(1).content());
        assertEquals(1, collected.size(), "the corrected call reached the collector");
        assertEquals("在校生", collected.getFirst().path("experience").asText());
        var progress = JobSearchWorkflow.inspect(outcome.transcript(), mapper);
        assertEquals(1, progress.attempts().size(), "the refused call is not an attempt");
        assertEquals(1, progress.requestedPages());
    }

    @Test
    void searchHistoryIsCommittedOnlyAfterSuccessfulSynchronousOrBrowserCollection() {
        for (boolean browser : java.util.List.of(false, true)) {
            for (String finish : java.util.List.of("LOGIN", "FIRST_PAGE_EMPTY", "NORMAL")) {
                long conversationId = store.create(1).conversation().id();
                var policy = new TranscriptPolicy(new AgentProperties());
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var toolCalls = new java.util.concurrent.atomic.AtomicInteger();
                var mapper = ConversationStore.JSON;
                java.util.Map<String, Object> collection = switch (finish) {
                    case "LOGIN" -> java.util.Map.of("status", "STOPPED", "stopReason", "BOSS_NOT_LOGGED_IN",
                            "pagesRead", 1, "jobIds", java.util.List.of());
                    case "FIRST_PAGE_EMPTY" -> java.util.Map.of("status", "COMPLETED",
                            "finishReason", "NO_MORE_RESULTS", "pagesRead", 1, "jobIds", java.util.List.of());
                    default -> java.util.Map.of("status", "COMPLETED", "finishReason", "MAX_PAGES",
                            "pagesRead", 1, "jobIds", java.util.List.of(11L));
                };
                com.arthur.jdragresume.agent.tool.DeferrableTool collector =
                        new com.arthur.jdragresume.agent.tool.DeferrableTool() {
                    public String name() { return "collect_boss_jobs"; }
                    public String description() { return "collect"; }
                    public com.arthur.jdragresume.agent.tool.ToolSchema parameters() {
                        return com.arthur.jdragresume.agent.tool.ToolSchema.object()
                                .string("keyword", "keyword", false, 50)
                                .string("city", "city", false, 10)
                                .string("jobType", "type", false, 10)
                                .string("experience", "experience", false, 10)
                                .integer("maxPages", "pages", false, 1, 3);
                    }
                    public Begin begin(com.fasterxml.jackson.databind.JsonNode args,
                                       com.arthur.jdragresume.agent.tool.AgentToolContext context) {
                        calls.incrementAndGet();
                        return browser ? new InBrowser("browser-extension", "collectBossJobs",
                                java.util.Map.of("keyword", "Java", "city", "深圳")) : new Done(collection);
                    }
                    public Object resume(com.fasterxml.jackson.databind.JsonNode args,
                                         com.fasterxml.jackson.databind.JsonNode report,
                                         com.arthur.jdragresume.agent.tool.AgentToolContext context) {
                        return collection;
                    }
                };
                AgentModel model = (system, messages, tools, timeout) -> {
                    int lastUser = 0;
                    for (int i = 0; i < messages.size(); i++) {
                        if (AgentMessage.USER.equals(messages.get(i).role())) lastUser = i;
                    }
                    if (messages.subList(lastUser + 1, messages.size()).stream()
                            .anyMatch(m -> AgentMessage.TOOL.equals(m.role()))) return AgentModel.Reply.answer("已处理");
                    return AgentModel.Reply.call(AgentMessage.ToolCall.of("collect-" + toolCalls.incrementAndGet(),
                            "collect_boss_jobs",
                            "{\"keyword\":\"Java\",\"city\":\"深圳\",\"jobType\":\"实习\"}"));
                };
                var loop = new AgentLoop(model,
                        new com.arthur.jdragresume.agent.tool.AgentToolRegistry(java.util.List.of(collector)),
                        mapper, new AgentProperties(), System::currentTimeMillis,
                        new BrowserTaskRegistry(store), store);
                var first = store.accept(1, conversationId, "找深圳 Java 实习", null, null, 90000,
                        messages -> policy.normalize(messages, null));
                store.updateSearchContext(first, old -> old.selectedResume(7).preferencesRead());
                var task = new java.util.concurrent.atomic.AtomicReference<AgentLoop.BrowserTaskEvent>();
                var outcome = runStoredCollection(loop, first, null, task);
                if (browser) {
                    assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state());
                    var persisted = ConversationStore.read(jdbc.queryForObject(
                            "SELECT search_context FROM agent_conversation WHERE id=?",
                            String.class, conversationId));
                    assertEquals(0, persisted.path("searched").size(),
                            "issuing a browser task must not mark the search completed");
                    assertEquals("深圳", persisted.path("city").asText(),
                            "filters must be persisted at begin, before the browser returns");
                    var pending = task.get();
                    assertNotNull(pending);
                    var report = new TranscriptPolicy.BrowserReport(pending.toolCallId(), pending.taskId(),
                            TranscriptPolicy.BrowserReport.COMPLETED, mapper.createObjectNode());
                    var resumed = store.accept(1, conversationId, null, null, report, 90000,
                            messages -> policy.normalize(messages, report.toolCallId(), call ->
                                    ToolEnvelope.error(call.name(), "BROWSER_TASK_ABANDONED", "abandoned")));
                    outcome = runStoredCollection(loop, resumed, report, new java.util.concurrent.atomic.AtomicReference<>());
                }
                assertEquals(AgentLoop.State.COMPLETED, outcome.state());
                var second = store.accept(1, conversationId, "再找几个", null, null, 90000,
                        messages -> policy.normalize(messages, null));
                assertEquals("深圳", store.searchContext(second).city());
                assertEquals("实习", store.searchContext(second).jobType());
                assertEquals("NORMAL".equals(finish) ? 1 : 0,
                        store.searchContext(second).searched().size(),
                        "only completed searches may enter cross-turn duplicate history");
                var repeat = runStoredCollection(loop, second, null,
                        new java.util.concurrent.atomic.AtomicReference<>());
                assertEquals("NORMAL".equals(finish) ? 1 : 2, calls.get(),
                        "platform stops must permit the exact same search next turn");
                if ("NORMAL".equals(finish)) {
                    assertTrue(repeat.transcript().stream()
                            .filter(m -> AgentMessage.TOOL.equals(m.role()))
                            .anyMatch(m -> m.content().contains("JOB_SEARCH_REPEAT")));
                } else if (browser) {
                    assertEquals(AgentLoop.State.AWAITING_BROWSER, repeat.state());
                }
            }
        }
    }

    private AgentLoop.Outcome runStoredCollection(AgentLoop loop, ConversationStore.Run run,
            TranscriptPolicy.BrowserReport report,
            java.util.concurrent.atomic.AtomicReference<AgentLoop.BrowserTaskEvent> browserTask) {
        var sink = new AgentEventSink() {
            @Override public void emit(String name, Object payload) {
                if ("browser_task_required".equals(name)) browserTask.set((AgentLoop.BrowserTaskEvent) payload);
            }
            @Override public void checkpoint(java.util.List<AgentMessage> messages, String summary) {
                store.checkpoint(run, messages, summary);
            }
        };
        var result = loop.run(run.input(), null,
                new com.arthur.jdragresume.agent.tool.AgentToolContext(
                        new com.arthur.jdragresume.entity.AppUser(), run),
                sink, () -> false, run.acceptedAt(),
                new AgentLoop.TurnOptions(null, java.util.List.of(), false, report));
        store.emit(run, "done", java.util.Map.of("state", result.state().name(), "steps", 1));
        return result;
    }

    private AgentLoop.Outcome runSearchTurn(AgentLoop loop, ConversationStore.Run run) {
        var sink = new AgentEventSink() {
            @Override public void emit(String name, Object payload) { }
            @Override public void checkpoint(java.util.List<AgentMessage> messages, String summary) {
                store.checkpoint(run, messages, summary);
            }
        };
        var outcome = loop.run(run.input(), null,
                new com.arthur.jdragresume.agent.tool.AgentToolContext(
                        new com.arthur.jdragresume.entity.AppUser(), run), sink, () -> false, run.acceptedAt());
        store.emit(run, "done", java.util.Map.of("state", "COMPLETED", "steps", 1));
        return outcome;
    }

    private void event(long id, int sequence, String type, String payload) {
        jdbc.update("INSERT INTO agent_conversation_event VALUES(?,?,?,?,?,?,NOW())", id, sequence, "turn", "run", type, payload);
    }
}
