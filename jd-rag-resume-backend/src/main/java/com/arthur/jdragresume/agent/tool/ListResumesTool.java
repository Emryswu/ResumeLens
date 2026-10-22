package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class ListResumesTool implements AgentTool {
    static final int PAGE_SIZE = 5;
    private static final java.util.regex.Pattern DIRECTION_SECTION = java.util.regex.Pattern.compile(
            "(?im)(?:^|\\n)\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?(?:求职意向|意向岗位|职业方向|专业技能|技术技能|技能清单|技术栈|技能|Technical Skills|Skills)");


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
        return "列出当前用户的简历（按更新时间倒序，每页 5 份），返回 resumeId、标题、姓名、updatedAt 和 directionEvidence（意向或技能原文节选，最多 301 字）。"
                + "标题和节选只用于选简历，不能替代正文；找岗位时即使只有一份且方向明显，也必须先 get_resume 成功读正文再采集，不能从本工具直接跳到 collect_boss_jobs。本会话已读过所选简历正文时可沿用。"
                + "用户说方向时选匹配方向的简历；没说方向但各版本同方向时选最近更新的；不同方向时列出各简历方向供用户选。"
                + "有后续页或结果被截断时先补齐信息，不能仅凭不完整列表假定所有简历同方向。可选 keyword 按标题或姓名过滤。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .string("keyword", "按简历标题或简历上的姓名过滤，可省略", false, 100)
                .integer("page", "页码，从 0 开始；有多页时继续读取", false, 0, 50);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        return choices(resumeService, arguments.path("page").asInt(0), ToolText.keyword(arguments));
    }

    static Choices choices(ResumeService service, int pageNumber, String keyword) {
        PageResponse<ResumeResponse> page = service.findAllForAgent(pageNumber, PAGE_SIZE, keyword);
        List<Item> items = page.content().stream()
                .map(resume -> new Item(resume.id(), resume.title(), resume.candidateName(), resume.updatedAt(),
                        directionEvidence(resume.rawText())))
                .toList();
        return new Choices(page.totalElements(), page.page(), page.totalPages(), items);
    }

    private static String directionEvidence(String text) {
        var section = DIRECTION_SECTION.matcher(text);
        return ToolText.clip(section.find() ? text.substring(section.start()).strip() : text, 300);
    }

    record Choices(long total, int page, int totalPages, List<Item> resumes) { }
    record Item(Long resumeId, String title, String candidateName, LocalDateTime updatedAt, String directionEvidence) { }
}
