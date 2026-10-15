package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Server-enforced order; arguments are resolved from live results at execution time. */
public record AgentPlan(List<Step> steps) {
    public static final String TOOL = "submit_plan";

    static AgentModel.ToolDefinition definition(ObjectMapper mapper) {
        var step = mapper.createObjectNode().put("type", "object");
        step.putObject("properties").putObject("tool").put("type", "string");
        step.withObject("properties").putObject("purpose").put("type", "string");
        step.putArray("required").add("tool").add("purpose");
        var schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("steps").put("type", "array").put("minItems", 1).set("items", step);
        schema.putArray("required").add("steps");
        return new AgentModel.ToolDefinition(TOOL, "多步问题先提交计划；工具失败或结果为空后可提交剩余步骤的新计划。简单查询直接调用工具。", schema);
    }

    static AgentPlan parse(String arguments, ObjectMapper mapper, AgentToolRegistry registry) {
        JsonNode root;
        try {
            root = mapper.readTree(arguments);
        } catch (Exception ex) {
            throw invalid("计划必须是合法 JSON");
        }
        if (root == null || !root.path("steps").isArray() || root.path("steps").isEmpty()) {
            throw invalid("计划必须包含非空 steps 列表");
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode step : root.path("steps")) {
            if (!step.path("tool").isTextual() || registry.find(step.path("tool").asText()).isEmpty()
                    || !step.path("purpose").isTextual() || step.path("purpose").asText().isBlank()
                    || step.path("purpose").asText().length() > 500) {
                throw invalid("每步必须包含已注册 tool 和不超过 500 字的 purpose");
            }
            steps.add(new Step(step.path("tool").asText(), step.path("purpose").asText()));
        }
        return new AgentPlan(List.copyOf(steps));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("AGENT_PLAN_INVALID", message);
    }

    public record Step(String tool, String purpose) {
    }
}
