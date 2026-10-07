package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.AgentModel;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> tools) {
        tools.stream()
                .sorted(Comparator.comparing(AgentTool::name))
                .forEach(tool -> {
                    if (this.tools.putIfAbsent(tool.name(), tool) != null) {
                        throw new IllegalStateException("duplicate agent tool name: " + tool.name());
                    }
                });
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public List<AgentModel.ToolDefinition> definitions() {
        return tools.values().stream()
                .map(tool -> new AgentModel.ToolDefinition(tool.name(), tool.description(), tool.parameters().toJson()))
                .toList();
    }
}
