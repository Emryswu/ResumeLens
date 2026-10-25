package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.ConversationStore;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Explicit conversation-scoped filter changes without opening a BOSS page. */
@Component
public class UpdateJobSearchContextTool implements AgentTool {
    private final ConversationStore conversations;

    public UpdateJobSearchContextTool(ConversationStore conversations) {
        this.conversations = conversations;
    }

    @Override public String name() { return "update_job_search_context"; }

    @Override public String description() {
        return "明确修改本会话岗位搜索条件，不打开浏览器。只传用户本轮明确要改变的字段；"
                + "未传继承，传空字符串或 null 清除。实习改全职时传 jobType=全职；"
                + "换城市传新 city，取消城市限制传 city=空字符串。"
                + "resumeId 只能清除；更换简历请用 get_resume 读取新正文，成功后自动记录。";
    }

    @Override public ToolSchema parameters() {
        return ToolSchema.object()
                .string("city", "城市；空字符串清除", false, 10)
                .string("jobType", "实习、全职、不限；空字符串清除", false, 10)
                .string("keyword", "岗位方向；空字符串清除", false, 50)
                .string("experience", "工作经验筛选；空字符串清除", false, 10)
                .integer("resumeId", "只能传 null 清除已选简历，更换请调用 get_resume", false, 1, Long.MAX_VALUE);
    }

    @Override public Object execute(JsonNode args, AgentToolContext context) {
        if (context.run() == null) {
            throw new BusinessException("AGENT_CONVERSATION_REQUIRED", "岗位条件只能在持久化会话中修改");
        }
        return conversations.updateSearchContext(context.run(), old -> {
            if (args.hasNonNull("resumeId")) {
                throw new BusinessException("INVALID_ARGUMENTS", "更换简历请先调用 get_resume");
            }
            return old.patch(args);
        });
    }
}
