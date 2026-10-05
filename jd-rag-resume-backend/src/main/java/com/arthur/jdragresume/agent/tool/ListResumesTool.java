package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class ListResumesTool implements AgentTool {
    private static final int PAGE_SIZE = 20;

    private final ResumeService resumeService;

    public ListResumesTool(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @Override
    public String name() {
        return "list_resumes";
    }

    @Override
    public String description() {
        return "列出当前用户的简历（最新在前，最多 20 份），返回 resumeId、标题、候选人姓名。"
                + "其他工具需要 resumeId 时先调用它，不要猜测 id。可选 keyword 按标题或姓名过滤。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .string("keyword", "按简历标题或候选人姓名过滤，可省略", false, 100);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        PageResponse<ResumeResponse> page = resumeService.findAll(0, PAGE_SIZE, ToolText.keyword(arguments));
        List<Item> items = page.content().stream()
                .map(resume -> new Item(resume.id(), resume.title(), resume.candidateName(), resume.updatedAt()))
                .toList();
        return Map.of("total", page.totalElements(), "resumes", items);
    }

    record Item(Long resumeId, String title, String candidateName, LocalDateTime updatedAt) {
    }
}
