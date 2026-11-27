package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.ai.ReasoningEffort;
import com.arthur.jdragresume.collect.CollectorMode;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatDispatchTests {

    @Test
    void rejectedDispatchRefundsRateLimitAndReleasesConversationLease() {
        AppUser alice = new AppUser();
        ReflectionTestUtils.setField(alice, "id", 1L);
        CurrentUserService users = new CurrentUserService(null) {
            @Override
            public AppUser getCurrentUser() {
                return alice;
            }
        };
        AgentProperties properties = new AgentProperties();
        properties.setMaxTurnsPerWindow(1);
        AgentLoop loop = mock(AgentLoop.class);
        ConversationStore store = mock(ConversationStore.class);
        ConversationStore.Run run = new ConversationStore.Run(
                1L, 10L, "turn-1", "run-1",
                new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("你好")), null),
                null, 1_000L
        );
        when(store.accept(eq(1L), eq(10L), eq("你好"), any(), any(), anyLong(), any())).thenReturn(run);

        SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter(() -> 1_000L);
        AgentChatService service = new AgentChatService(
                users,
                new TranscriptPolicy(properties),
                loop,
                rateLimiter,
                command -> {
                    throw new TaskRejectedException("queue full");
                },
                properties,
                mock(AgentMemoryService.class),
                new AgentToolRegistry(List.of()),
                new BrowserTaskRegistry(),
                store
        );

        BusinessException busy = assertThrows(BusinessException.class, () ->
                service.chat(10L, "你好", null, null, false, ReasoningEffort.HIGH, CollectorMode.AUTO));
        assertEquals("AGENT_BUSY", busy.getCode());
        verify(store).fail(eq(run), eq("AGENT_BUSY"), any());
        assertTrue(rateLimiter.tryAcquire(SlidingWindowRateLimiter.Key.agent(1L), 1, 60_000L),
                "rejected dispatch must release the turn rate-limit permit");
    }

    @Test
    void failedAcceptRefundsRateLimitPermit() {
        AppUser alice = new AppUser();
        ReflectionTestUtils.setField(alice, "id", 2L);
        CurrentUserService users = new CurrentUserService(null) {
            @Override
            public AppUser getCurrentUser() {
                return alice;
            }
        };
        AgentProperties properties = new AgentProperties();
        properties.setMaxTurnsPerWindow(1);
        AgentLoop loop = mock(AgentLoop.class);
        ConversationStore store = mock(ConversationStore.class);
        when(store.accept(eq(2L), eq(20L), eq("你好"), any(), any(), anyLong(), any()))
                .thenThrow(new BusinessException("AGENT_CONVERSATION_BUSY", "conversation is running"));

        SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter(() -> 1_000L);
        AgentChatService service = new AgentChatService(
                users,
                new TranscriptPolicy(properties),
                loop,
                rateLimiter,
                Runnable::run,
                properties,
                mock(AgentMemoryService.class),
                new AgentToolRegistry(List.of()),
                new BrowserTaskRegistry(),
                store
        );

        BusinessException conflict = assertThrows(BusinessException.class, () ->
                service.chat(20L, "你好", null, null, false, ReasoningEffort.HIGH, CollectorMode.AUTO));
        assertEquals("AGENT_CONVERSATION_BUSY", conflict.getCode());
        assertTrue(rateLimiter.tryAcquire(SlidingWindowRateLimiter.Key.agent(2L), 1, 60_000L),
                "conflict before execution must release the turn rate-limit permit");
    }
}
