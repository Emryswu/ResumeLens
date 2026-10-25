package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobSearchContextTests {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode patch(String city, String keyword, String jobType) {
        return json.createObjectNode().put("city", city).put("keyword", keyword).put("jobType", jobType);
    }

    @Test
    void inheritsOnlyMissingFieldsAndRejectsRepeatedEffectiveSearchAcrossTurns() {
        JobSearchContext first = JobSearchContext.empty().patch(patch("深圳", "Java", "实习"));
        assertEquals("在校生", first.effectiveExperience());
        assertEquals("深圳", first.effective(json.createObjectNode()).path("city").asText());
        assertEquals("在校生", first.effective(json.createObjectNode()).path("experience").asText());

        JobSearchContext.Search original = first.asSearch();
        JobSearchContext nextTurn = first.collected(original);
        assertTrue(nextTurn.alreadySearched(nextTurn.patch(json.createObjectNode()).asSearch()),
                "the same inherited keyword/city/experience must be rejected before opening BOSS");
        assertFalse(nextTurn.alreadySearched(nextTurn.patch(json.createObjectNode().put("keyword", "Spring Boot")).asSearch()));
        assertEquals(1, nextTurn.searched().size());

        var transcript = List.of(AgentMessage.user("找深圳 Java 实习"), AgentMessage.user("再找几个"));
        var snapshot = JobSearchWorkflow.inspect(transcript, json, nextTurn);
        assertTrue(snapshot.internship());
        assertEquals(0, snapshot.attempts().size(), "budgets reset per user turn, unlike search history");
        assertEquals(0, snapshot.requestedPages());
    }

    @Test
    void duplicateHistoryNormalizesKeywordCaseAndWhitespace() {
        var search = new JobSearchContext.Search("  JAVA \t   Backend  ", "深圳", "在校生");
        assertEquals("java backend", search.keyword());
        var context = JobSearchContext.empty().collected(search);
        assertTrue(context.alreadySearched(new JobSearchContext.Search("java backend", "深圳", "在校生")));
        assertTrue(context.alreadySearched(new JobSearchContext.Search(" Java  Backend ", "深圳", "在校生")));
        assertEquals(1, context.collected(new JobSearchContext.Search("java backend", "深圳", "在校生")).searched().size());
        assertFalse(context.alreadySearched(new JobSearchContext.Search("java backend", "北京", "在校生")));
    }

    @Test
    void explicitFullTimeAndCityClearOverrideEarlierState() {
        var previous = JobSearchContext.empty().patch(patch("深圳", "Java", "实习"));
        var fullTime = previous.patch(json.createObjectNode().put("jobType", "全职"));
        assertEquals("全职", fullTime.jobType());
        assertEquals("不限", fullTime.effectiveExperience());
        assertEquals("深圳", fullTime.city());
        assertEquals("Java", fullTime.keyword());
        assertEquals("不限", fullTime.effective(json.createObjectNode()).path("experience").asText());

        var cleared = fullTime.patch(json.createObjectNode().put("city", ""));
        assertNull(cleared.city());
        assertEquals("全国", cleared.effective(json.createObjectNode()).path("city").asText());
        assertEquals("全职", cleared.jobType());
        assertNull(JobSearchContext.empty().resumeId(), "a fresh conversation starts without a chosen resume");
        assertFalse(JobSearchContext.empty().hasConditions());
    }

    @Test
    void selectedResumeCanBeClearedWithoutGuessingFromConversationText() {
        JobSearchContext selected = JobSearchContext.empty().selectedResume(9);
        assertEquals(9L, selected.resumeId());
        assertNull(selected.patch(json.createObjectNode().putNull("resumeId")).resumeId());
        assertEquals(9L, selected.patch(json.createObjectNode().put("keyword", "AI")).resumeId());
    }
}
