package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchConditionPolicyTests {
    private final ObjectMapper json = new ObjectMapper();

    private JobSearchContext internship(String experience) {
        var fields = json.createObjectNode().put("jobType", "实习");
        if (experience != null) fields.put("experience", experience);
        return JobSearchContext.empty().patch(fields);
    }

    @Test
    void onlyAModelChosenLooserExperienceForAStudentInternshipConflicts() {
        assertTrue(SearchConditionPolicy.conflict(internship("不限"), true).isPresent());
        assertTrue(SearchConditionPolicy.conflict(internship("应届生"), true).isPresent());
        assertTrue(SearchConditionPolicy.conflict(internship(null), true).isEmpty(), "the default is already 在校生");
        assertTrue(SearchConditionPolicy.conflict(internship("在校生"), true).isEmpty());
        assertTrue(SearchConditionPolicy.conflict(internship("不限"), false).isEmpty());
        assertTrue(SearchConditionPolicy.conflict(internship("不限"), null).isEmpty(), "unknown status is not a restriction");
        assertTrue(SearchConditionPolicy.conflict(internship("不限").confirmedByUser("experience"), true).isEmpty());
        var fullTime = JobSearchContext.empty().patch(json.createObjectNode().put("jobType", "全职").put("experience", "不限"));
        assertTrue(SearchConditionPolicy.conflict(fullTime, true).isEmpty());
    }

    @Test
    void userApprovalSurvivesInheritanceButNotAChangedValueOrJobType() {
        var approved = internship("不限").confirmedByUser("experience");
        assertTrue(approved.patch(json.createObjectNode().put("keyword", "Agent")).userConfirmed().contains("experience"));
        assertTrue(approved.patch(json.createObjectNode().put("experience", "不限")).userConfirmed().contains("experience"));
        assertFalse(approved.patch(json.createObjectNode().put("experience", "在校生")).userConfirmed().contains("experience"));
        assertFalse(approved.patch(json.createObjectNode().put("jobType", "全职")).userConfirmed().contains("experience"));
        assertFalse(approved.patch(json.createObjectNode().put("experience", "")).userConfirmed().contains("experience"));
        assertTrue(approved.collected(approved.asSearch()).userConfirmed().contains("experience"));
        assertTrue(approved.selectedResume(4).preferencesRead().userConfirmed().contains("experience"));
        assertThrows(IllegalArgumentException.class, () -> approved.confirmedByUser("city"),
                "only fields with a hard rule can be approved");
    }

    @Test
    void storedConditionsWithoutSourcesStillLoad() throws Exception {
        var restored = json.readValue("{\"city\":\"深圳\",\"jobType\":\"实习\",\"experience\":\"不限\","
                + "\"preferencesLoaded\":true,\"searched\":[]}", JobSearchContext.class);
        assertTrue(restored.userConfirmed().isEmpty(), "older rows have no user approvals");
        assertTrue(SearchConditionPolicy.conflict(restored, true).isPresent());
        var roundTrip = json.readValue(json.writeValueAsString(internship("不限").confirmedByUser("experience")),
                JobSearchContext.class);
        assertTrue(roundTrip.userConfirmed().contains("experience"));
        var stored = ConversationStore.JSON.readValue(ConversationStore.JSON.writeValueAsString(
                internship("不限").confirmedByUser("experience")), JobSearchContext.class);
        assertTrue(stored.userConfirmed().contains("experience"), "the conversation store keeps approvals");
        var forged = ConversationStore.JSON.readValue("{\"jobType\":\"实习\",\"experience\":\"不限\","
                + "\"userConfirmed\":[\"experience\",\"city\"]}", JobSearchContext.class);
        assertEquals(java.util.List.of("experience"), forged.userConfirmed(), "unknown field names are dropped on load");
    }
}
