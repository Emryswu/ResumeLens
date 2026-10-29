package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.RelaxJobSearchConditionTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.agent.tool.UpdateJobSearchContextTool;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ToolNotStartedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A model-chosen search filter must not loosen a hard condition from a higher layer. Today the only such
 * condition is "the user is a student looking for an internship, so BOSS experience must be 在校生". Only a
 * user approval (the confirmation card of relax_job_search_condition) can loosen it; nothing the model
 * writes into tool arguments counts as that approval.
 */
class SearchConditionLayeringTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppUser user = new AppUser();
    private final AtomicReference<JobSearchContext> stored = new AtomicReference<>();
    private final List<JsonNode> collected = new ArrayList<>();
    private ConversationStore store;
    private ConversationStore.Run run;
    private Boolean student = true;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", 7L);
        stored.set(JobSearchContext.empty().selectedResume(3).preferencesRead());
        store = mock(ConversationStore.class);
        when(store.searchContext(any())).thenAnswer(invocation -> stored.get());
        when(store.updateSearchContext(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            UnaryOperator<JobSearchContext> update = invocation.getArgument(1);
            JobSearchContext next = update.apply(stored.get());
            stored.set(next);
            return next;
        });
        run = new ConversationStore.Run(7L, 11L, "turn", "run", null, null, 0);
    }

    @Test
    void aStudentInternshipSearchWithModelChosenUnlimitedExperienceIsRefusedBeforeRunningAndTheRetryCollects() throws Exception {
        AgentLoop.Outcome outcome = runTurn("找杭州的 AI 实习",
                "{\"keyword\":\"AI 应用开发\",\"city\":\"杭州\",\"jobType\":\"实习\",\"experience\":\"不限\"}",
                "{\"keyword\":\"AI 应用开发\",\"city\":\"杭州\",\"jobType\":\"实习\"}");

        List<AgentMessage> results = toolResults(outcome);
        JsonNode refused = mapper.readTree(results.get(0).content());
        assertEquals(SearchConditionPolicy.CONFLICT, refused.path("error").path("code").asText(), refused.toString());
        assertFalse(ToolEnvelope.executed(refused), "a conflicting call never reached the collector");
        String message = refused.path("error").path("message").asText();
        assertTrue(message.contains("experience") && message.contains("在校生") && message.contains("relax_job_search_condition"),
                "the refusal names the field, the required value and how to ask the user: " + message);
        assertEquals(1, collected.size(), "only the corrected call collects");
        assertEquals("在校生", collected.getFirst().path("experience").asText());
        var progress = JobSearchWorkflow.inspect(outcome.transcript(), mapper, stored.get());
        assertEquals(1, progress.attempts().size(), "the refused call is not charged to the search budget");
        assertNull(stored.get().experience(), "the refused value is not written into the session");
    }

    @Test
    void theRuleOnlyAppliesToKnownStudentsLookingForInternships() {
        student = null;
        runTurn("找实习", "{\"keyword\":\"Java\",\"city\":\"深圳\",\"jobType\":\"实习\",\"experience\":\"不限\"}");
        assertEquals("不限", collected.getLast().path("experience").asText(), "an unknown status is never a restriction");

        student = false;
        stored.set(JobSearchContext.empty().selectedResume(3).preferencesRead());
        runTurn("找实习", "{\"keyword\":\"Go\",\"city\":\"深圳\",\"jobType\":\"实习\",\"experience\":\"不限\"}");
        assertEquals("不限", collected.getLast().path("experience").asText());

        student = true;
        stored.set(JobSearchContext.empty().selectedResume(3).preferencesRead());
        runTurn("找全职", "{\"keyword\":\"Python\",\"city\":\"深圳\",\"jobType\":\"全职\",\"experience\":\"不限\"}");
        assertEquals("不限", collected.getLast().path("experience").asText(), "full-time searches are not internship searches");
    }

    @Test
    void aUserApprovedRelaxationIsRecordedAsUserSourceAndIsNotReimposedOnLaterSearches() throws Exception {
        stored.set(stored.get().patch(mapper.createObjectNode().put("jobType", "实习").put("city", "杭州")));
        var tool = new RelaxJobSearchConditionTool(store, userId -> student);
        assertTrue(tool.requiresConfirmation(), "only the user's approval of this card may loosen a hard condition");
        JsonNode args = mapper.createObjectNode().put("experience", "不限");
        Object preview = tool.preview(args, context());
        String shown = mapper.writeValueAsString(preview);
        assertTrue(shown.contains("在校生") && shown.contains("不限"), "the card shows what is loosened: " + shown);
        assertNull(stored.get().experience(), "previewing changes nothing");

        tool.execute(args, context());
        assertEquals("不限", stored.get().experience());
        assertTrue(stored.get().userConfirmed().contains("experience"));

        runTurn("再找找", "{\"keyword\":\"Agent 开发\"}");
        assertEquals("不限", collected.getLast().path("experience").asText(), "the approved value is inherited");
        runTurn("换个方向", "{\"keyword\":\"RAG\",\"experience\":\"不限\"}");
        assertEquals("不限", collected.getLast().path("experience").asText(), "repeating the approved value is allowed");
        assertEquals(2, collected.size());
    }

    @Test
    void aModelTighteningDropsTheUserApprovalSoLooseningAgainNeedsANewApproval() throws Exception {
        stored.set(stored.get().patch(mapper.createObjectNode().put("jobType", "实习"))
                .patch(mapper.createObjectNode().put("experience", "不限")).confirmedByUser("experience"));
        runTurn("只看在校生的", "{\"keyword\":\"Java\",\"experience\":\"在校生\"}");
        assertFalse(stored.get().userConfirmed().contains("experience"));

        AgentLoop.Outcome outcome = runTurn("再放宽", "{\"keyword\":\"Go\",\"experience\":\"不限\"}");
        JsonNode refused = mapper.readTree(toolResults(outcome).getFirst().content());
        assertEquals(SearchConditionPolicy.CONFLICT, refused.path("error").path("code").asText());
        assertEquals(1, collected.size());
    }

    @Test
    void theModelCannotClaimUserAuthorityThroughArguments() throws Exception {
        AgentLoop.Outcome outcome = runTurn("找实习",
                "{\"keyword\":\"Java\",\"city\":\"深圳\",\"jobType\":\"实习\",\"experience\":\"不限\","
                        + "\"userConfirmed\":[\"experience\"],\"source\":\"user\"}");
        JsonNode refused = mapper.readTree(toolResults(outcome).getFirst().content());
        assertFalse(refused.path("ok").asBoolean(), refused.toString());
        assertTrue(collected.isEmpty());
        assertTrue(stored.get().userConfirmed().isEmpty());
    }

    @Test
    void theUpdateToolCannotBeUsedToLoosenTheConditionBehindTheCollectorsBack() {
        stored.set(stored.get().patch(mapper.createObjectNode().put("jobType", "实习")));
        var update = new UpdateJobSearchContextTool(store, userId -> student);
        var refused = assertThrows(ToolNotStartedException.class, () -> update.execute(
                mapper.createObjectNode().put("experience", "不限"), context()));
        assertEquals(SearchConditionPolicy.CONFLICT, refused.getCode());
        assertNull(stored.get().experience(), "the session keeps its previous conditions");

        update.execute(mapper.createObjectNode().put("city", "杭州"), context());
        assertEquals("杭州", stored.get().city(), "other fields still update");
        assertThrows(ToolNotStartedException.class, () -> update.execute(
                mapper.createObjectNode().put("userConfirmed", "experience").put("experience", "不限"), context()));
    }

    @Test
    void clearingTheExperienceFilterFallsBackToTheDefaultInsteadOfLoosening() {
        stored.set(stored.get().patch(mapper.createObjectNode().put("jobType", "实习").put("experience", "在校生")));
        runTurn("找实习", "{\"keyword\":\"Java\",\"experience\":\"\"}");
        assertEquals("在校生", collected.getLast().path("experience").asText());
        assertNull(stored.get().experience(), "an explicit clear is stored as no filter, not as 不限");
    }

    @Test
    void relaxationIsRefusedWithoutAPersistedConversationOrWhenNothingIsLoosened() {
        var tool = new RelaxJobSearchConditionTool(store, userId -> student);
        assertThrows(BusinessException.class, () -> tool.preview(mapper.createObjectNode().put("experience", "不限"),
                new AgentToolContext(user)));
        stored.set(stored.get().patch(mapper.createObjectNode().put("jobType", "实习")));
        assertThrows(BusinessException.class, () -> tool.preview(mapper.createObjectNode().put("experience", "在校生"),
                context()), "asking the user to approve the value already required is pointless");
    }

    @Test
    void askingToRelaxInTheVeryFirstTurnShowsTheCardAndTheApprovedSearchCollectsWithoutAnyConflict() throws Exception {
        // A fresh conversation: no jobType stored yet. The user says "我在校，找实习，经验不限的也看看".
        stored.set(JobSearchContext.empty().selectedResume(3).preferencesRead());
        var relax = new RelaxJobSearchConditionTool(store, userId -> student);
        AgentModel model = (system, messages, tools, timeout) -> {
            List<AgentMessage> results = messages.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).toList();
            if (results.isEmpty()) {
                return AgentModel.Reply.call(AgentMessage.ToolCall.of("relax", relax.name(),
                        "{\"jobType\":\"实习\",\"experience\":\"不限\"}"));
            }
            if (results.size() == 1) {
                return AgentModel.Reply.call(AgentMessage.ToolCall.of("collect", JobSearchWorkflow.TOOL,
                        "{\"keyword\":\"AI 应用开发\",\"city\":\"杭州\"}"));
            }
            return AgentModel.Reply.answer("好的");
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(collector(), relax)), mapper, new AgentProperties(),
                System::currentTimeMillis, new BrowserTaskRegistry(), store, userId -> student);
        var policy = new TranscriptPolicy(new AgentProperties());
        var sink = new AgentEventSink() {
            final List<Object> confirmations = new ArrayList<>();
            @Override public void emit(String name, Object payload) {
                if ("confirmation_required".equals(name)) confirmations.add(payload);
            }
            @Override public void checkpoint(List<AgentMessage> messages, String summary) { }
        };

        AgentLoop.Outcome paused = loop.run(policy.normalize(new ArrayList<>(List.of(
                AgentMessage.user("我在校，找杭州实习，经验不限的也看看"))), null), null, context(), sink, () -> false);
        assertEquals(AgentLoop.State.AWAITING_CONFIRMATION, paused.state(), "the card is shown on the first try");
        assertEquals(1, sink.confirmations.size());
        String card = mapper.writeValueAsString(((AgentLoop.PendingConfirmation) sink.confirmations.getFirst()).preview());
        assertTrue(card.contains("\"from\":\"在校生\"") && card.contains("\"to\":\"不限\""), card);
        assertNull(stored.get().jobType(), "nothing is stored before the user decides");

        var approval = new TranscriptPolicy.Approval("relax", true);
        AgentLoop.Outcome done = loop.run(policy.normalize(new ArrayList<>(paused.transcript()), approval), approval,
                context(), sink, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, done.state());
        assertTrue(done.transcript().stream().noneMatch(m -> m.content() != null
                && m.content().contains(SearchConditionPolicy.CONFLICT)), "no refusal anywhere on the approved path");
        assertEquals("实习", stored.get().jobType());
        assertEquals(1, collected.size());
        assertEquals("不限", collected.getFirst().path("experience").asText());
    }

    @Test
    void everyRefusalPointsToANextStepThatWorksFromTheSameState() {
        // From an empty session, the collector's refusal says to call relax with jobType; that call must be accepted.
        stored.set(JobSearchContext.empty().selectedResume(3).preferencesRead());
        String refusal = SearchConditionPolicy.conflict(JobSearchContext.empty().patch(
                mapper.createObjectNode().put("jobType", "实习").put("experience", "不限")), true).orElseThrow();
        assertTrue(refusal.contains("jobType=实习"), refusal);
        var relax = new RelaxJobSearchConditionTool(store, userId -> student);
        assertDoesNotThrow(() -> relax.preview(mapper.createObjectNode().put("jobType", "实习").put("experience", "不限"),
                context()));
        // Without jobType the relax tool explains how to proceed instead of sending the model back to the collector.
        var missingType = assertThrows(BusinessException.class, () -> relax.preview(
                mapper.createObjectNode().put("experience", "不限"), context()));
        assertTrue(missingType.getMessage().contains("jobType=实习"), missingType.getMessage());
        assertFalse(missingType.getMessage().contains("collect_boss_jobs 里传即可"), missingType.getMessage());
    }

    private AgentToolContext context() {
        return new AgentToolContext(user, run);
    }

    private List<AgentMessage> toolResults(AgentLoop.Outcome outcome) {
        return outcome.transcript().stream().filter(message -> AgentMessage.TOOL.equals(message.role())).toList();
    }

    /** Runs one user turn whose model calls the collector once per argument set, then answers. */
    private AgentLoop.Outcome runTurn(String question, String... calls) {
        AtomicInteger next = new AtomicInteger();
        AgentModel model = (system, messages, tools, timeout) -> {
            int index = next.getAndIncrement();
            if (index < calls.length) {
                return AgentModel.Reply.call(AgentMessage.ToolCall.of("c" + collected.size() + "-" + index,
                        JobSearchWorkflow.TOOL, calls[index]));
            }
            return AgentModel.Reply.answer("好的");
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(collector())), mapper, new AgentProperties(),
                System::currentTimeMillis, new BrowserTaskRegistry(), store, userId -> student);
        var policy = new TranscriptPolicy(new AgentProperties());
        return loop.run(policy.normalize(new ArrayList<>(List.of(AgentMessage.user(question))), null), null, context(),
                new AgentEventSink() {
                    @Override public void emit(String name, Object payload) { }
                    @Override public void checkpoint(List<AgentMessage> messages, String summary) { }
                }, () -> false);
    }

    private DeferrableTool collector() {
        return new DeferrableTool() {
            public String name() { return JobSearchWorkflow.TOOL; }
            public String description() { return "collect"; }
            public ToolSchema parameters() {
                return ToolSchema.object()
                        .string("keyword", "keyword", false, 50)
                        .string("city", "city", false, 10)
                        .string("jobType", "type", false, 10)
                        .string("experience", "experience", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(JsonNode args, AgentToolContext context) {
                collected.add(args.deepCopy());
                return new Done(Map.of("status", "COMPLETED", "finishReason", "MAX_PAGES", "pagesRead", 1,
                        "jobIds", List.of((long) collected.size()), "dailyPagesLeft", 20));
            }
            public Object resume(JsonNode args, JsonNode report, AgentToolContext context) {
                throw new AssertionError("collection finishes on the server");
            }
        };
    }
}
