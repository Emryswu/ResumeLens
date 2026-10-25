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
        Flyway.configure().dataSource(source).target("19").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at,display_name,email,password_hash,username) VALUES"
                + "(1,NOW(),NOW(),'Alice','alice@test.local','hash','alice'),(2,NOW(),NOW(),'Bob','bob@test.local','hash','bob')");
        store = new ConversationStore(jdbc, new DataSourceTransactionManager(source));
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
