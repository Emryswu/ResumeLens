package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Profile is user-controlled; absence means unknown, never an inferred restriction. */
@Component
public class GetJobSearchPreferencesTool implements AgentTool {
    private final JobSearchPreferencesService preferences;

    public GetJobSearchPreferencesTool(JobSearchPreferencesService preferences) {
        this.preferences = preferences;
    }

    @Override public String name() { return "get_job_search_preferences"; }
    @Override public String description() {
        return "读取当前用户自己填写的实习时长、每周到岗天数、最早到岗日期和偏好地区。找实习和核对岗位硬条件前先读一次；"
                + "未填写的字段是未知，preferredDistrict 默认仅供排序偏好，districtRequired=true 才是硬限制。";
    }
    @Override public ToolSchema parameters() { return ToolSchema.object(); }
    @Override public Object execute(JsonNode arguments, AgentToolContext context) {
        return preferences.forUser(context.user().getId());
    }
}
