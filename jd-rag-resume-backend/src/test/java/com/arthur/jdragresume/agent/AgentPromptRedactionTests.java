package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.GetResumeTool;
import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPromptRedactionTests {
    private static final String RAW = "13812345678 arthur@example.com";
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void regularAndClosingCallsRedactSystemUserAndRealGetResumeResultsWhileCheckpointsKeepOriginals(int maxSteps) throws Exception {
        var client = mock(AiClient.class);
        var resumes = mock(ResumeService.class);
        String resumeText = "Java Spring Boot 项目，联系方式 " + RAW;
        when(resumes.findById(1L)).thenReturn(new ResumeResponse(1L, 1L, "owner", "简历 " + RAW,
                null, null, null, null, null, null, null, resumeText, null, null));
        AtomicInteger calls = new AtomicInteger();
        when(client.chatWithTools(anyList(), anyList(), any(), any())).thenAnswer(inv -> {
            String request = json.writeValueAsString(inv.getArgument(0));
            assertRedacted(request);
            assertTrue(request.contains("summary [手机号] [邮箱]"));
            if (calls.incrementAndGet() == 1) {
                return json.readTree("""
                        {"tool_calls":[{"id":"c1","function":{"name":"get_resume","arguments":"{\\"resumeId\\":1}"}}]}
                        """);
            }
            assertTrue(request.contains("Java Spring Boot 项目，联系方式 [手机号] [邮箱]"));
            assertTrue(request.contains("\"role\":\"tool\""));
            if (maxSteps == 1) assertTrue(((List<?>) inv.getArgument(1)).isEmpty());
            return json.readTree("{\"content\":\"已读取简历。\"}");
        });
        var properties = new AgentProperties();
        properties.setMaxSteps(maxSteps);
        var loop = new AgentLoop(new OpenAiCompatibleAgentModel(client),
                new AgentToolRegistry(List.of(new GetResumeTool(resumes))), json, properties, System::currentTimeMillis);
        List<List<AgentMessage>> checkpoints = new ArrayList<>();
        AgentEventSink sink = new AgentEventSink() {
            public void emit(String event, Object payload) { }
            public void checkpoint(List<AgentMessage> messages, String summary) { checkpoints.add(List.copyOf(messages)); }
        };
        var input = new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("读取简历，电话邮箱 " + RAW)), null);

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgentLoop.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        AgentLoop.Outcome result;
        try {
            result = loop.run(input, null, new AgentToolContext(new AppUser()), sink, () -> false,
                    System.currentTimeMillis(), new AgentLoop.TurnOptions("summary " + RAW, List.of(), false));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
        assertFalse(logs.list.isEmpty());
        assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains("13812345678")
                || event.getFormattedMessage().contains("arthur@example.com")));

        assertEquals(maxSteps == 1 ? AgentLoop.State.STEP_LIMIT : AgentLoop.State.COMPLETED, result.state());
        assertEquals(2, calls.get());
        assertTrue(result.transcript().getFirst().content().contains(RAW));
        assertTrue(result.transcript().stream().filter(m -> AgentMessage.TOOL.equals(m.role()))
                .anyMatch(m -> m.content().contains(resumeText)));
        assertTrue(checkpoints.stream().flatMap(List::stream).anyMatch(m -> m.content() != null && m.content().contains(resumeText)));
    }

    @Test
    void adapterCopiesAllRolesReasoningToolArgumentsAndSchemasWithoutTouchingServerTranscript() {
        var client = mock(AiClient.class);
        var transcript = List.of(AgentMessage.user(RAW), AgentMessage.assistant(RAW,
                        List.of(AgentMessage.ToolCall.of("c1", "lookup", "{\"contact\":\"" + RAW + "\"}")), RAW),
                AgentMessage.tool("c1", "简历片段/BOSS JD " + RAW));
        var schema = json.valueToTree(Map.of("type", "object", "description", RAW));
        var tools = List.of(new AgentModel.ToolDefinition("lookup", RAW, schema));
        when(client.chatWithTools(anyList(), anyList(), any(), any())).thenAnswer(inv -> {
            assertRedacted(json.writeValueAsString(inv.getArgument(0)));
            assertRedacted(json.writeValueAsString(inv.getArgument(1)));
            return json.readTree("{\"content\":\"ok\"}");
        });
        var model = new OpenAiCompatibleAgentModel(client);
        model.next(RAW, transcript, tools, Duration.ofSeconds(5));
        model.next(RAW, transcript, tools, Duration.ofSeconds(5));
        assertEquals(RAW, transcript.getFirst().content());
        assertEquals(RAW, transcript.get(1).reasoningContent());
        assertTrue(transcript.get(1).toolCalls().getFirst().arguments().contains(RAW));
        assertEquals(RAW, schema.path("description").asText());
        verify(client, times(2)).chatWithTools(anyList(), anyList(), any(), any());
    }

    private static void assertRedacted(String request) {
        assertFalse(request.contains("13812345678"));
        assertFalse(request.contains("arthur@example.com"));
        assertTrue(request.contains("[手机号] [邮箱]"));
    }

    @Test
    void historySummarizationAlsoUsesTheRedactingClientBoundary() {
        var client = mock(AiClient.class);
        when(client.chatWithTools(anyList(), anyList(), any(), any())).thenAnswer(inv -> {
            assertRedacted(json.writeValueAsString(inv.getArgument(0)));
            return json.readTree("{\"content\":\"摘要\"}");
        });
        assertEquals("摘要", ShortTermMemory.summarize(new OpenAiCompatibleAgentModel(client), json,
                new AgentProperties(), RAW, List.of(AgentMessage.user(RAW), AgentMessage.tool("c1", RAW)), Duration.ofSeconds(5)));
    }
}
