package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatGptResponsesProtocolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatGptResponsesProtocol protocol = new ChatGptResponsesProtocol(mapper);

    @Test
    void translatesHistoryAndToolsWithoutUnsupportedParameters() throws Exception {
        JsonNode request = protocol.request(mapper.readTree("""
                {
                  "model":"example-model",
                  "temperature":0.2,
                  "max_tokens":1500,
                  "messages":[
                    {"role":"system","content":"instructions"},
                    {"role":"user","content":"find jobs"},
                    {"role":"assistant","content":null,"tool_calls":[
                      {"id":"call_1","type":"function","function":{"name":"list_jobs","arguments":"{}"}}
                    ]},
                    {"role":"tool","tool_call_id":"call_1","content":"result"}
                  ],
                  "tools":[{"type":"function","function":{
                    "name":"list_jobs","description":"List jobs","parameters":{"type":"object"}
                  }}]
                }
                """));
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.has("max_output_tokens")).isFalse();
        assertThat(request.has("max_tokens")).isFalse();
        assertThat(request.has("temperature")).isFalse();
        assertThat(request.has("reasoning")).isFalse();
        assertThat(request.path("input").get(0).path("role").asText()).isEqualTo("developer");
        assertThat(request.path("input").get(2).path("type").asText()).isEqualTo("function_call");
        assertThat(request.path("input").get(2).path("call_id").asText()).isEqualTo("call_1");
        assertThat(request.path("input").get(3).path("type").asText()).isEqualTo("function_call_output");
        assertThat(request.path("input").get(3).path("call_id").asText()).isEqualTo("call_1");
        assertThat(request.path("tools").get(0).path("type").asText()).isEqualTo("namespace");
        assertThat(request.path("tools").get(0).path("tools").get(0).path("name").asText()).isEqualTo("list_jobs");
    }

    @Test
    void forwardsEachExplicitReasoningEffort() {
        for (String effort : java.util.List.of("low", "medium", "high", "xhigh", "max")) {
            var chat = mapper.createObjectNode().put("model", "gpt-6.2-sol").put("reasoning_effort", effort);
            chat.putArray("messages").addObject().put("role", "user").put("content", "test");
            JsonNode request = protocol.request(chat);
            assertThat(request.path("reasoning").path("effort").asText()).isEqualTo(effort);
            assertThat(request.has("reasoning_effort")).isFalse();
        }
    }

    @Test
    void preservesJsonOutputMode() throws Exception {
        JsonNode request = protocol.request(mapper.readTree("""
                {"model":"example-model","messages":[{"role":"user","content":"Return JSON"}],
                 "response_format":{"type":"json_object"}}
                """));
        assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.has("response_format")).isFalse();
    }

    @Test
    void usesCompletedEventForTextCallsAndUsage() throws Exception {
        JsonNode reply = protocol.readCompleted(new StringReader("""
                data: {"type":"response.output_text.delta","delta":"partial"}

                data: {"type":"response.completed","response":{"output":[
                data: {"type":"message","content":[{"type":"output_text","text":"complete"}]},
                data: {"type":"function_call","namespace":"resumelens","call_id":"call_1","name":"list_jobs","arguments":"{}"}
                data: ],"usage":{"input_tokens":7,"output_tokens":9}}}

                """));
        JsonNode message = reply.path("choices").get(0).path("message");
        assertThat(message.path("content").asText()).isEqualTo("complete");
        assertThat(message.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_1");
        assertThat(message.path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("list_jobs");
        assertThat(reply.path("usage").path("prompt_tokens").asInt()).isEqualTo(7);
        assertThat(reply.path("usage").path("completion_tokens").asInt()).isEqualTo(9);
    }

    @Test
    void refusesPartialStreamsAndUsageLimitFailures() {
        assertThatThrownBy(() -> protocol.readCompleted(new StringReader("""
                data: {"type":"response.output_text.delta","delta":"partial"}

                """))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> protocol.readCompleted(new StringReader("""
                data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}

                """))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> protocol.readCompleted(new StringReader("""
                data: {"type":"response.incomplete","response":{}}

                """))).isInstanceOf(BusinessException.class);
    }
}
