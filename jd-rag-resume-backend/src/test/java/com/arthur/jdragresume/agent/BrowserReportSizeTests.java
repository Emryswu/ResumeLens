package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.controller.AgentController;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BrowserReportSizeTests {
    @Test void multibyteBrowserReportExceedingByteBudgetReturns413() throws Exception {
        var users = mock(CurrentUserService.class);
        var user = mock(AppUser.class);
        when(users.getCurrentUser()).thenReturn(user);
        var loop = mock(AgentLoop.class);
        var service = new AgentChatService(users, null, loop, new SlidingWindowRateLimiter(), null,
                new AgentProperties(), null, new AgentToolRegistry(List.of()), null, mock(ConversationStore.class));
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        long id = 1L;
        String body = """
                {"conversationId":%d,"browserResult":{"toolCallId":"call","taskId":"task","outcome":"completed","report":"%s"}}
                """.formatted(id, "中".repeat(70_000));
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(loop);
    }
}
