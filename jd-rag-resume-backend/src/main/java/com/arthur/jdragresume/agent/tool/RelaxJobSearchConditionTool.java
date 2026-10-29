package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.ConversationStore;
import com.arthur.jdragresume.agent.JobSearchContext;
import com.arthur.jdragresume.agent.JobSearchProfile;
import com.arthur.jdragresume.agent.SearchConditionPolicy;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * The only way to loosen a hard search condition: the loop pauses on a confirmation card and runs this only after
 * the user approves the exact value. The value is then stored as the user's own choice, so later searches in the
 * conversation inherit it instead of having the hard condition re-imposed.
 */
@Component
public class RelaxJobSearchConditionTool implements AgentTool {
    public static final String TOOL = "relax_job_search_condition";

    private final ConversationStore conversations;
    private final JobSearchProfile profile;

    public RelaxJobSearchConditionTool(ConversationStore conversations, JobSearchProfile profile) {
        this.conversations = conversations;
        this.profile = profile;
    }

    @Override public String name() { return "relax_job_search_condition"; }

    @Override public String description() {
        return "仅当用户明确表示要放宽经验筛选（例如在校生找实习但也想看经验不限的岗位）时调用。"
                + "会先请用户在界面上确认，用户同意后才把本会话的经验筛选改为传入的值；不要自行调用来绕开条件冲突。";
    }

    @Override public ToolSchema parameters() {
        return ToolSchema.object()
                .string("experience", "放宽后的经验筛选，如 不限", true, 10)
                .string("jobType", "岗位类型；会话里还没记下时（如第一轮）传 实习，省略则沿用会话", false, 10);
    }

    @Override public boolean requiresConfirmation() {
        return true;
    }

    @Override public Object preview(JsonNode arguments, AgentToolContext context) {
        JobSearchContext current = current(context);
        String requested = arguments.path("experience").asText().strip();
        JobSearchContext next = current.patch(arguments);
        Boolean student = profile.currentlyStudent(context.user().getId());
        // Whether a card is needed, and what to do instead, both come from the policy that refuses the search.
        if (SearchConditionPolicy.conflict(next, student).isEmpty()) {
            throw new BusinessException("INVALID_ARGUMENTS", SearchConditionPolicy.relaxationNotNeeded(next, student));
        }
        return new Preview("experience", SearchConditionPolicy.STUDENT_EXPERIENCE, requested,
                "你填写的求职条件是在校生找实习，默认只看在校生岗位");
    }

    @Override public Object execute(JsonNode arguments, AgentToolContext context) {
        current(context);
        JobSearchContext saved = conversations.updateSearchContext(context.run(),
                old -> old.patch(arguments).confirmedByUser("experience"));
        return new Relaxed("experience", saved.experience(), "user");
    }

    private JobSearchContext current(AgentToolContext context) {
        if (context.run() == null) {
            throw new BusinessException("AGENT_CONVERSATION_REQUIRED", "岗位条件只能在持久化会话中修改");
        }
        return conversations.searchContext(context.run());
    }

    public record Preview(String field, String from, String to, String reason) {
    }

    public record Relaxed(String field, String value, String source) {
    }
}
